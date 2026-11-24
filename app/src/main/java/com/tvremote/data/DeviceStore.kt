package com.tvremote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves the user's TVs in SharedPreferences. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun load(): List<TvDevice> = runCatching {
        val array = JSONArray(prefs.getString(KEY_LIST, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TvDevice(
                name = o.getString("name"),
                host = o.getString("host"),
                type = DeviceType.valueOf(o.getString("type")),
                paired = o.optBoolean("paired"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(devices: List<TvDevice>) {
        val array = JSONArray()
        devices.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("type", it.type.name)
                    .put("paired", it.paired)
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    var lastDeviceId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_LAST = "last"
    }
}
