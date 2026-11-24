package com.tvremote.atv

import android.os.Build
import com.tvremote.data.NotPairedException
import com.tvremote.data.RemoteKey
import com.tvremote.data.TvApp
import com.tvremote.data.TvController
import com.tvremote.data.TvInfo
import com.tvremote.proto.ProtoFraming
import com.tvremote.proto.ProtoMessage
import com.tvremote.proto.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote protocol v2 (the one used by Google's "Google TV" phone app), TLS on port 6466.
 * Works with TCL Google TV and Android TV models, plus Sony, Philips, Hisense, Chromecast with Google TV, etc.
 */
class AtvRemote(
    private val host: String,
    private val identity: AtvIdentity,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
) : TvController {

    private val _info = MutableStateFlow(TvInfo())
    override val info: StateFlow<TvInfo> = _info

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sender = Dispatchers.IO.limitedParallelism(1) // keeps key presses in order
    private val writeLock = Any()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private var reader: Job? = null

    private var features = ACTIVE_FEATURES
    private var imeCounter = 0
    private var fieldCounter = 0
    private var hasImeCounter = false
    private var hasFieldCounter = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = identity.createSslContext().socketFactory.createSocket() as SSLSocket
        socket = s
        try {
            s.connect(InetSocketAddress(host, REMOTE_PORT), 5_000)
        } catch (e: IOException) {
            close()
            throw e // unreachable: wrong IP, TV off, different network
        }
        var gotFirstMessage = false
        try {
            s.soTimeout = 8_000
            s.startHandshake()
            // Handshake: configure -> set_active -> remote_start. Ready once the TV accepts set_active.
            while (true) {
                val msg = read(s)
                gotFirstMessage = true
                if (handle(msg)) break
            }
        } catch (e: IOException) {
            close()
            // A TV that doesn't know our certificate drops the connection before saying anything.
            if (!gotFirstMessage && e !is SocketTimeoutException) throw NotPairedException(e)
            throw e
        }
        // TV pings every ~5 s; if nothing arrives for 20 s the connection is dead.
        s.soTimeout = 20_000
        reader = scope.launch(Dispatchers.IO) {
            try {
                while (!closed) handle(read(s))
            } catch (e: Exception) {
                if (!closed) {
                    close()
                    onFailure(IOException("Disconnected from the TV", e))
                }
            }
        }
    }

    override fun press(key: RemoteKey) {
        val code = key.atv ?: return
        send { message(F_KEY_INJECT) { int(1, code); int(2, DIRECTION_SHORT) } }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        send {
            message(F_IME_BATCH_EDIT) {
                int(1, imeCounter)
                int(2, fieldCounter)
                message(3) {
                    int(1, 1) // insert
                    message(2) { int(1, text.length); int(2, text.length); string(3, text) }
                }
            }
        }
    }

    override fun launchApp(app: TvApp) {
        send { message(F_APP_LINK) { string(1, app.id) } }
    }

    override suspend fun apps(): List<TvApp> = DEFAULT_APPS

    override fun close() {
        closed = true
        reader?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(block: ProtoWriter.() -> Unit) {
        val payload = ProtoWriter().apply(block).toByteArray()
        scope.launch(sender) {
            val s = socket
            if (s == null) {
                onFailure(IOException("Not connected to the TV"))
                return@launch
            }
            try {
                write(s, payload)
            } catch (e: IOException) {
                if (!closed) {
                    close()
                    onFailure(IOException("Disconnected from the TV", e))
                }
            }
        }
    }

    private fun write(s: SSLSocket, payload: ByteArray) =
        synchronized(writeLock) { ProtoFraming.write(s.outputStream, payload) }

    private fun reply(s: SSLSocket, block: ProtoWriter.() -> Unit) =
        write(s, ProtoWriter().apply(block).toByteArray())

    private fun read(s: SSLSocket) = ProtoMessage.parse(ProtoFraming.read(s.inputStream))

    /** Handles one message from the TV. Returns true once the TV is ready for commands. */
    private fun handle(msg: ProtoMessage): Boolean {
        val s = socket ?: return false
        when {
            msg.has(F_CONFIGURE) -> {
                val cfg = msg.message(F_CONFIGURE)!!
                val supported = cfg.int(1)
                if (supported != 0) features = ACTIVE_FEATURES and supported
                cfg.message(2)?.let { device ->
                    _info.update { it.copy(model = device.string(1), manufacturer = device.string(2)) }
                }
                reply(s) {
                    message(F_CONFIGURE) {
                        int(1, features)
                        message(2) {
                            string(1, Build.MODEL)
                            string(2, Build.MANUFACTURER)
                            int(3, 1)
                            string(4, "1")
                            string(5, "atvremote")
                            string(6, "1.0.0")
                        }
                    }
                }
            }
            msg.has(F_SET_ACTIVE) -> {
                reply(s) { message(F_SET_ACTIVE) { int(1, features) } }
                return true
            }
            msg.has(F_PING_REQUEST) -> {
                val value = msg.message(F_PING_REQUEST)!!.int(1)
                reply(s) { message(F_PING_RESPONSE) { int(1, value) } }
            }
            msg.has(F_START) -> {
                _info.update { it.copy(poweredOn = msg.message(F_START)!!.bool(1)) }
                return true
            }
            msg.has(F_SET_VOLUME) -> {
                val v = msg.message(F_SET_VOLUME)!!
                _info.update { it.copy(volume = v.int(7), volumeMax = v.int(6), muted = v.bool(8)) }
            }
            msg.has(F_IME_KEY_INJECT) -> {
                val inject = msg.message(F_IME_KEY_INJECT)!!
                inject.message(1)?.let { app ->
                    imeCounter = app.int(1)
                    hasImeCounter = imeCounter != 0
                    val pkg = app.string(12)
                    if (pkg.isNotEmpty()) _info.update { it.copy(currentApp = pkg) }
                }
                inject.message(2)?.let { field ->
                    fieldCounter = field.int(1)
                    hasFieldCounter = fieldCounter != 0
                }
            }
            msg.has(F_IME_SHOW_REQUEST) -> {
                msg.message(F_IME_SHOW_REQUEST)?.message(2)?.let {
                    fieldCounter = it.int(1)
                    hasFieldCounter = true
                }
            }
            msg.has(F_IME_BATCH_EDIT) -> {
                val edit = msg.message(F_IME_BATCH_EDIT)!!
                if (!hasImeCounter) imeCounter = edit.int(1)
                if (!hasFieldCounter) fieldCounter = edit.int(2)
            }
        }
        return false
    }

    companion object {
        const val REMOTE_PORT = 6466

        // RemoteMessage field numbers
        private const val F_CONFIGURE = 1
        private const val F_SET_ACTIVE = 2
        private const val F_PING_REQUEST = 8
        private const val F_PING_RESPONSE = 9
        private const val F_KEY_INJECT = 10
        private const val F_IME_KEY_INJECT = 20
        private const val F_IME_BATCH_EDIT = 21
        private const val F_IME_SHOW_REQUEST = 22
        private const val F_START = 40
        private const val F_SET_VOLUME = 50
        private const val F_APP_LINK = 90

        private const val DIRECTION_SHORT = 3

        // Feature flags: PING | KEY | IME | POWER | VOLUME | APP_LINK
        private const val ACTIVE_FEATURES = 1 or 2 or 4 or 32 or 64 or 512

        /** App links open the app on the TV. "market://launch?id=<package>" opens any installed app. */
        val DEFAULT_APPS = listOf(
            TvApp("https://www.youtube.com", "YouTube"),
            TvApp("https://www.netflix.com/title", "Netflix"),
            TvApp("https://app.primevideo.com", "Prime Video"),
            TvApp("https://www.disneyplus.com", "Disney+"),
            TvApp("market://launch?id=com.spotify.tv.android", "Spotify"),
            TvApp("market://launch?id=com.google.android.youtube.tvmusic", "YT Music"),
            TvApp("market://launch?id=com.android.vending", "Play Store"),
            TvApp("market://launch?id=com.android.tv.settings", "Settings"),
        )

        fun isUnreachable(e: Throwable) =
            e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
                (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
    }
}
