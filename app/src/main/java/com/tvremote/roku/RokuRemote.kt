package com.tvremote.roku

import com.tvremote.data.RemoteKey
import com.tvremote.data.TvApp
import com.tvremote.data.TvController
import com.tvremote.data.TvInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Roku External Control Protocol (HTTP on port 8060). Covers TCL Roku TVs. */
class RokuRemote(
    private val host: String,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
) : TvController {

    private val _info = MutableStateFlow(TvInfo())
    override val info: StateFlow<TvInfo> = _info

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sender = Dispatchers.IO.limitedParallelism(1)

    override suspend fun connect() {
        val d = deviceInfo(host)
        _info.value = TvInfo(manufacturer = d.vendor, model = d.model, poweredOn = d.poweredOn)
    }

    override fun press(key: RemoteKey) {
        val name = key.roku ?: return
        post("/keypress/$name")
    }

    override fun sendText(text: String) {
        text.forEach { ch -> post("/keypress/Lit_" + URLEncoder.encode(ch.toString(), "UTF-8").replace("+", "%20")) }
    }

    override fun launchApp(app: TvApp) = post("/launch/${app.id}")

    override suspend fun apps(): List<TvApp> = withContext(Dispatchers.IO) {
        val xml = request(host, "GET", "/query/apps")
        APP_REGEX.findAll(xml)
            .map { TvApp(it.groupValues[1], unescape(it.groupValues[2])) }
            .toList()
    }

    override fun close() = Unit

    private fun post(path: String) {
        scope.launch(sender) {
            try {
                request(host, "POST", path)
            } catch (e: IOException) {
                onFailure(e)
            }
        }
    }

    data class DeviceInfo(val name: String, val vendor: String?, val model: String?, val poweredOn: Boolean?)

    companion object {
        const val PORT = 8060
        private val APP_REGEX = Regex("<app\\s+id=\"([^\"]+)\"[^>]*>([^<]*)</app>")

        suspend fun deviceInfo(host: String): DeviceInfo = withContext(Dispatchers.IO) {
            val xml = request(host, "GET", "/query/device-info")
            fun tag(name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)
                ?.let(::unescape)?.takeIf { it.isNotBlank() }
            val vendor = tag("vendor-name")
            val model = tag("model-name")
            val name = tag("user-device-name") ?: tag("friendly-device-name")
                ?: listOfNotNull(vendor, model).joinToString(" ").ifBlank { "Roku TV" }
            DeviceInfo(name, vendor, model, tag("power-mode")?.let { it == "PowerOn" })
        }

        private fun request(host: String, method: String, path: String): String {
            val conn = URL("http://$host:$PORT$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 3_000
                conn.readTimeout = 5_000
                if (method == "POST") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()
                }
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.bufferedReader().use { it.readText() }
                    403 -> throw IOException(
                        "The TV blocked the command. On the TV open Settings › System › Advanced system " +
                            "settings › Control by mobile apps, and set Network access to Default or Permissive."
                    )
                    else -> throw IOException("The TV returned HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }

        private fun unescape(s: String) = s
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
