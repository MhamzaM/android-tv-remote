package com.tvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.tvremote.atv.AtvRemote
import com.tvremote.data.DeviceType
import com.tvremote.data.TvDevice
import com.tvremote.roku.RokuRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finds TVs on the local network:
 *  - Google TV / Android TV (incl. TCL) advertise "_androidtvremote2._tcp" over mDNS.
 *  - Roku TVs (incl. TCL Roku TV) answer SSDP searches for "roku:ecp".
 */
class TvDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    private val _found = MutableStateFlow<List<TvDevice>>(emptyList())
    val found: StateFlow<List<TvDevice>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var scanJob: Job? = null

    fun scan(scope: CoroutineScope, durationMs: Long = 12_000) {
        scanJob?.cancel()
        stopNsd()
        _scanning.value = true
        startNsd()
        scanJob = scope.launch {
            launch { repeat(2) { searchRoku(scope); delay(1_000) } }
            delay(durationMs)
            stopNsd()
            _scanning.value = false
        }
    }

    fun stop() {
        scanJob?.cancel()
        stopNsd()
        _scanning.value = false
    }

    private fun add(device: TvDevice) = _found.update { list ->
        if (list.any { it.id == device.id }) list else list + device
    }

    // ---- Google TV / Android TV via mDNS ----

    private fun startNsd() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsdListener = listener
        runCatching { nsd.discoverServices(ATV_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun stopNsd() {
        nsdListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        nsdListener = null
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        resolveNext()
    }

    /** Older Android versions can only resolve one service at a time, so resolve sequentially. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        val done = {
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
        }
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null && !host.contains(':')) { // skip IPv6 for simplicity
                        add(TvDevice(cleanName(info.serviceName), host, DeviceType.ANDROID_TV))
                    }
                    done()
                }
            })
        }.onFailure { done() }
    }

    // ---- Roku via SSDP ----

    private suspend fun searchRoku(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("tvremote-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val request = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "ST: roku:ecp\r\n" +
                        "MX: 2\r\n\r\n"
                    ).toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val seen = HashSet<String>()
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val location = LOCATION.find(text)?.groupValues?.get(1)
                    val host = location?.let { runCatching { URI(it).host }.getOrNull() }
                        ?: packet.address.hostAddress ?: continue
                    if (seen.add(host)) {
                        scope.launch {
                            val info = runCatching { RokuRemote.deviceInfo(host) }.getOrNull()
                            add(TvDevice(info?.name ?: "Roku TV ($host)", host, DeviceType.ROKU))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Network unavailable etc. — mDNS results may still arrive.
        } finally {
            lock?.release()
        }
    }

    companion object {
        private const val ATV_SERVICE = "_androidtvremote2._tcp"
        private val LOCATION = Regex("(?im)^location:\\s*(\\S+)")

        /** Some Android versions return mDNS names with escapes like "Living\\032Room". */
        private fun cleanName(name: String) =
            Regex("\\\\(\\d{3})").replace(name) { it.groupValues[1].toInt().toChar().toString() }

        /** Works out what kind of TV is at an IP address typed by the user. */
        suspend fun probe(host: String): DeviceType? = withContext(Dispatchers.IO) {
            if (runCatching { RokuRemote.deviceInfo(host) }.isSuccess) return@withContext DeviceType.ROKU
            val atv = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, AtvRemote.REMOTE_PORT), 2_500) }
            }.isSuccess
            if (atv) DeviceType.ANDROID_TV else null
        }
    }
}
