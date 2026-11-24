package com.tvremote.data

import android.view.KeyEvent
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class DeviceType(val label: String) {
    ANDROID_TV("Google TV / Android TV"),
    ROKU("Roku TV"),
}

data class TvDevice(
    val name: String,
    val host: String,
    val type: DeviceType,
    val paired: Boolean = false,
) {
    val id: String get() = "${type.name}@$host"
}

data class TvApp(val id: String, val name: String)

/** Live state reported by the TV (whatever the protocol provides). */
data class TvInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val poweredOn: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val currentApp: String? = null,
)

/** Thrown when an Android TV refuses our certificate, i.e. pairing is needed. */
class NotPairedException(cause: Throwable? = null) : IOException("This phone isn't paired with the TV", cause)

/**
 * Remote buttons with their Android TV key code (android.view.KeyEvent) and Roku ECP key name.
 * null means the TV type has no such key, and the button is hidden.
 */
enum class RemoteKey(val atv: Int?, val roku: String?) {
    POWER(KeyEvent.KEYCODE_POWER, "Power"),
    UP(KeyEvent.KEYCODE_DPAD_UP, "Up"),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT, "Left"),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, "Right"),
    OK(KeyEvent.KEYCODE_DPAD_CENTER, "Select"),
    BACK(KeyEvent.KEYCODE_BACK, "Back"),
    HOME(KeyEvent.KEYCODE_HOME, "Home"),
    MENU(KeyEvent.KEYCODE_MENU, "Info"), // Roku's "*" options key
    SETTINGS(KeyEvent.KEYCODE_SETTINGS, null),
    INPUT(KeyEvent.KEYCODE_TV_INPUT, null),
    GUIDE(KeyEvent.KEYCODE_GUIDE, null),
    SEARCH(KeyEvent.KEYCODE_SEARCH, "Search"),
    ASSISTANT(KeyEvent.KEYCODE_ASSIST, null),

    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP, "VolumeUp"),
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN, "VolumeDown"),
    MUTE(KeyEvent.KEYCODE_VOLUME_MUTE, "VolumeMute"),
    CHANNEL_UP(KeyEvent.KEYCODE_CHANNEL_UP, "ChannelUp"),
    CHANNEL_DOWN(KeyEvent.KEYCODE_CHANNEL_DOWN, "ChannelDown"),

    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play"),
    REWIND(KeyEvent.KEYCODE_MEDIA_REWIND, "Rev"),
    FAST_FORWARD(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "Fwd"),
    REPLAY(null, "InstantReplay"),

    DEL(KeyEvent.KEYCODE_DEL, "Backspace"),
    ENTER(KeyEvent.KEYCODE_ENTER, "Enter"),
    NUM_0(KeyEvent.KEYCODE_0, "Lit_0"),
    NUM_1(KeyEvent.KEYCODE_1, "Lit_1"),
    NUM_2(KeyEvent.KEYCODE_2, "Lit_2"),
    NUM_3(KeyEvent.KEYCODE_3, "Lit_3"),
    NUM_4(KeyEvent.KEYCODE_4, "Lit_4"),
    NUM_5(KeyEvent.KEYCODE_5, "Lit_5"),
    NUM_6(KeyEvent.KEYCODE_6, "Lit_6"),
    NUM_7(KeyEvent.KEYCODE_7, "Lit_7"),
    NUM_8(KeyEvent.KEYCODE_8, "Lit_8"),
    NUM_9(KeyEvent.KEYCODE_9, "Lit_9"),

    // Direct input switching (TCL Google TV and TCL Roku TV both support these)
    INPUT_TV(KeyEvent.KEYCODE_TV, "InputTuner"),
    INPUT_HDMI1(KeyEvent.KEYCODE_TV_INPUT_HDMI_1, "InputHDMI1"),
    INPUT_HDMI2(KeyEvent.KEYCODE_TV_INPUT_HDMI_2, "InputHDMI2"),
    INPUT_HDMI3(KeyEvent.KEYCODE_TV_INPUT_HDMI_3, "InputHDMI3"),
    INPUT_HDMI4(KeyEvent.KEYCODE_TV_INPUT_HDMI_4, "InputHDMI4"),
    INPUT_AV(KeyEvent.KEYCODE_TV_INPUT_COMPOSITE_1, "InputAV1");

    fun supportedBy(type: DeviceType): Boolean = when (type) {
        DeviceType.ANDROID_TV -> atv != null
        DeviceType.ROKU -> roku != null
    }

    companion object {
        val digits = listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)
    }
}

/** One connection to one TV. Implementations do network work off the main thread. */
interface TvController {
    val info: StateFlow<TvInfo>

    /** Suspends until the TV is ready for commands. May throw [NotPairedException] (Android TV). */
    suspend fun connect()
    fun press(key: RemoteKey)
    fun sendText(text: String)
    fun launchApp(app: TvApp)
    suspend fun apps(): List<TvApp>
    fun close()
}
