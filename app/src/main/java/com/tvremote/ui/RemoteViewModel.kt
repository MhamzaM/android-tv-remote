package com.tvremote.ui

import android.app.Application
import android.os.Build
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvremote.atv.AtvIdentity
import com.tvremote.atv.AtvPairing
import com.tvremote.atv.AtvRemote
import com.tvremote.data.DeviceStore
import com.tvremote.data.DeviceType
import com.tvremote.data.NotPairedException
import com.tvremote.data.RemoteKey
import com.tvremote.data.TvApp
import com.tvremote.data.TvController
import com.tvremote.data.TvDevice
import com.tvremote.data.TvInfo
import com.tvremote.discovery.TvDiscovery
import com.tvremote.roku.RokuRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    /** Android TV pairing. [codeShown] = the TV is displaying a code to type in. */
    data class Pairing(val codeShown: Boolean, val busy: Boolean = false, val error: String? = null) : Connection
    data class Failed(val message: String) : Connection
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DeviceStore(app)
    private val discovery = TvDiscovery(app)

    private val _saved = MutableStateFlow(store.load())
    val saved: StateFlow<List<TvDevice>> = _saved

    /** Discovered TVs that aren't saved yet. */
    val discovered: StateFlow<List<TvDevice>> = combine(discovery.found, _saved) { found, saved ->
        found.filter { f -> saved.none { it.id == f.id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val scanning: StateFlow<Boolean> = discovery.scanning

    private val _current = MutableStateFlow<TvDevice?>(null)
    val current: StateFlow<TvDevice?> = _current

    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection

    private val controller = MutableStateFlow<TvController?>(null)
    val info: StateFlow<TvInfo> = controller
        .flatMapLatest { it?.info ?: flowOf(TvInfo()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TvInfo())

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps: StateFlow<List<TvApp>> = _apps

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var pairing: AtvPairing? = null
    private var connectJob: Job? = null

    init {
        // Reopen the last TV straight away.
        store.lastDeviceId?.let { id -> _saved.value.firstOrNull { it.id == id } }?.let { open(it) }
            ?: scan()
    }

    fun scan() = discovery.scan(viewModelScope)

    fun open(device: TvDevice) {
        if (_saved.value.none { it.id == device.id }) saveDevices(_saved.value + device)
        store.lastDeviceId = device.id
        _current.value = _saved.value.first { it.id == device.id }
        discovery.stop()
        connect()
    }

    fun closeRemote() {
        tearDown()
        store.lastDeviceId = null
        _current.value = null
        _connection.value = Connection.Idle
        scan()
    }

    fun forget(device: TvDevice) {
        saveDevices(_saved.value.filterNot { it.id == device.id })
        if (store.lastDeviceId == device.id) store.lastDeviceId = null
    }

    fun rename(device: TvDevice, name: String) {
        if (name.isBlank()) return
        updateDevice(device.copy(name = name.trim()))
    }

    /** Adds a TV by IP. type == null means detect it automatically. */
    fun addManual(name: String, host: String, type: DeviceType?) {
        val ip = host.trim()
        viewModelScope.launch {
            val resolved = type ?: TvDiscovery.probe(ip)
            if (resolved == null) {
                _messages.emit("No supported TV found at $ip. Check the IP and that the TV is on.")
                return@launch
            }
            val label = name.trim().ifEmpty {
                if (resolved == DeviceType.ROKU) runCatching { RokuRemote.deviceInfo(ip).name }.getOrDefault("Roku TV")
                else "TV ($ip)"
            }
            open(TvDevice(label, ip, resolved))
        }
    }

    fun connect() {
        val device = _current.value ?: return
        tearDown()
        _connection.value = Connection.Connecting
        connectJob = viewModelScope.launch {
            val c: TvController = when (device.type) {
                DeviceType.ANDROID_TV -> AtvRemote(device.host, identity(), viewModelScope, ::onFailure)
                DeviceType.ROKU -> RokuRemote(device.host, viewModelScope, ::onFailure)
            }
            try {
                c.connect()
                controller.value = c
                _connection.value = Connection.Connected
                if (!device.paired) updateDevice(device.copy(paired = true))
                _apps.value = runCatching { c.apps() }.getOrDefault(emptyList())
            } catch (e: CancellationException) {
                c.close()
                throw e
            } catch (e: NotPairedException) {
                c.close()
                startPairing()
            } catch (e: Exception) {
                c.close()
                _connection.value = Connection.Failed(friendly(e, device))
            }
        }
    }

    // ---- Pairing (Android TV / Google TV) ----

    fun startPairing() {
        val device = _current.value ?: return
        pairing?.close()
        _connection.value = Connection.Pairing(codeShown = false, busy = true)
        viewModelScope.launch {
            val p = AtvPairing(device.host, identity(), "${Build.MANUFACTURER} ${Build.MODEL}")
            pairing = p
            try {
                p.begin()
                _connection.value = Connection.Pairing(codeShown = true)
            } catch (e: Exception) {
                p.close()
                _connection.value = Connection.Failed("Couldn't start pairing: ${friendly(e, device)}")
            }
        }
    }

    fun submitPairingCode(code: String) {
        val p = pairing ?: return
        _connection.value = Connection.Pairing(codeShown = true, busy = true)
        viewModelScope.launch {
            try {
                p.finish(code)
                pairing = null
                _current.value?.let { updateDevice(it.copy(paired = true)) }
                connect()
            } catch (e: Exception) {
                _connection.value = Connection.Pairing(codeShown = true, error = e.message ?: "Pairing failed")
            }
        }
    }

    fun cancelPairing() {
        pairing?.close()
        pairing = null
        _connection.value = Connection.Failed("Pairing cancelled")
    }

    // ---- Commands ----

    fun press(key: RemoteKey) {
        val c = controller.value
        if (c == null || _connection.value != Connection.Connected) {
            if (_connection.value is Connection.Failed) connect()
            return
        }
        c.press(key)
    }

    fun sendText(text: String) {
        controller.value?.sendText(text)
    }

    fun launchApp(app: TvApp) {
        controller.value?.launchApp(app)
    }

    fun launchPackage(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isNotEmpty()) launchApp(TvApp("market://launch?id=$pkg", pkg))
    }

    /** Phone volume buttons control the TV while the remote is connected. */
    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (_connection.value != Connection.Connected) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) press(key)
        return true
    }

    fun onAppForeground() {
        if (_current.value != null && _connection.value is Connection.Failed) connect()
    }

    // ---- Internals ----

    private fun onFailure(e: Throwable) {
        viewModelScope.launch {
            val disconnected = e.message?.startsWith("Disconnected") == true ||
                e.message?.startsWith("Not connected") == true
            if (disconnected) {
                if (_connection.value == Connection.Connected) {
                    controller.value = null
                    _connection.value = Connection.Failed("Disconnected from the TV")
                }
            } else {
                _messages.emit(e.message ?: "Command failed")
            }
        }
    }

    private fun tearDown() {
        connectJob?.cancel()
        controller.value?.close()
        controller.value = null
        pairing?.close()
        pairing = null
    }

    private suspend fun identity(): AtvIdentity =
        withContext(Dispatchers.Default) { AtvIdentity.get(getApplication()) }

    private fun updateDevice(device: TvDevice) {
        saveDevices(_saved.value.map { if (it.id == device.id) device else it })
        if (_current.value?.id == device.id) _current.value = device
    }

    private fun saveDevices(list: List<TvDevice>) {
        _saved.value = list
        store.save(list)
    }

    private fun friendly(e: Throwable, device: TvDevice): String = when {
        AtvRemote.isUnreachable(e) || e is java.net.SocketTimeoutException ->
            "Can't reach ${device.name} at ${device.host}. Make sure the TV is on and on the same Wi-Fi as this phone."
        else -> e.message ?: e.javaClass.simpleName
    }

    override fun onCleared() {
        tearDown()
        discovery.stop()
    }
}
