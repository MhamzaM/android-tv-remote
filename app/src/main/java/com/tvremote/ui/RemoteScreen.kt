package com.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvremote.data.DeviceType
import com.tvremote.data.RemoteKey
import com.tvremote.data.TvApp
import com.tvremote.data.TvDevice
import com.tvremote.data.TvInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, device: TvDevice) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var showKeyboard by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val type = device.type
    val press: (RemoteKey) -> Unit = vm::press

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeRemote) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "All TVs") }
                },
                title = {
                    Column {
                        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            statusText(connection, info),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connection == Connection.Connected) Color(0xFF46C17B) else MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showKeyboard = true }) { Icon(Icons.Default.Keyboard, "Type on TV") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val c = connection) {
                Connection.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Connection.Failed -> ErrorBanner(
                    message = c.message,
                    onRetry = vm::connect,
                    onPair = if (type == DeviceType.ANDROID_TV) vm::startPairing else null,
                )
                else -> Unit
            }

            TopRow(type, press)
            DPad(press)
            NavRow(type, press)
            RockerRow(press)
            MediaRow(type, press)
            Extras(type, apps, press, vm::launchApp, vm::launchPackage)
            Spacer(Modifier.height(8.dp))
        }
    }

    (connection as? Connection.Pairing)?.let { state ->
        PairingDialog(
            state = state,
            deviceName = device.name,
            onSubmit = vm::submitPairingCode,
            onCancel = vm::cancelPairing,
        )
    }
    if (showKeyboard) {
        KeyboardDialog(
            type = type,
            onSend = vm::sendText,
            onKey = press,
            onDismiss = { showKeyboard = false },
        )
    }
}

private fun statusText(connection: Connection, info: TvInfo): String = when (connection) {
    Connection.Idle -> "Not connected"
    Connection.Connecting -> "Connecting…"
    is Connection.Pairing -> "Pairing…"
    is Connection.Failed -> "Not connected"
    Connection.Connected -> buildList {
        add("Connected")
        listOfNotNull(info.manufacturer, info.model).filter { it.isNotBlank() }.joinToString(" ")
            .takeIf { it.isNotBlank() }?.let { add(it) }
        if (info.muted == true) add("Muted")
        else if (info.volume != null && (info.volumeMax ?: 0) > 0) add("Vol ${info.volume}")
    }.joinToString(" · ")
}

// ---------- Button rows ----------

@Composable
private fun TopRow(type: DeviceType, press: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RemoteButton(
            onPress = { press(RemoteKey.POWER) },
            description = "Power",
            modifier = Modifier.size(60.dp),
            color = PowerRed,
            contentColor = Color.White,
        ) { Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(30.dp)) }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (RemoteKey.INPUT.supportedBy(type)) SmallKey(RemoteKey.INPUT, "Input source", press) { Icon(Icons.Default.Input, null) }
            SmallKey(RemoteKey.SEARCH, "Search", press) { Icon(Icons.Default.Search, null) }
            if (RemoteKey.SETTINGS.supportedBy(type)) SmallKey(RemoteKey.SETTINGS, "Settings", press) { Icon(Icons.Default.Settings, null) }
        }
    }
}

@Composable
private fun SmallKey(
    key: RemoteKey,
    description: String,
    press: (RemoteKey) -> Unit,
    size: Int = 52,
    repeat: Boolean = false,
    content: @Composable () -> Unit,
) {
    RemoteButton(
        onPress = { press(key) },
        description = description,
        modifier = Modifier.size(size.dp),
        repeat = repeat,
    ) { content() }
}

@Composable
private fun DPad(press: (RemoteKey) -> Unit) {
    val arrowShape = RoundedCornerShape(40.dp)
    Box(
        Modifier
            .size(264.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
    ) {
        val arrow = Modifier.size(width = 112.dp, height = 82.dp)
        val sideArrow = Modifier.size(width = 82.dp, height = 112.dp)
        RemoteButton({ press(RemoteKey.UP) }, "Up", arrow.align(Alignment.TopCenter), arrowShape, Color.Transparent, repeat = true) {
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(42.dp))
        }
        RemoteButton({ press(RemoteKey.DOWN) }, "Down", arrow.align(Alignment.BottomCenter), arrowShape, Color.Transparent, repeat = true) {
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(42.dp))
        }
        RemoteButton({ press(RemoteKey.LEFT) }, "Left", sideArrow.align(Alignment.CenterStart), arrowShape, Color.Transparent, repeat = true) {
            Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.size(42.dp))
        }
        RemoteButton({ press(RemoteKey.RIGHT) }, "Right", sideArrow.align(Alignment.CenterEnd), arrowShape, Color.Transparent, repeat = true) {
            Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(42.dp))
        }
        RemoteButton(
            onPress = { press(RemoteKey.OK) },
            description = "OK",
            modifier = Modifier.align(Alignment.Center).size(104.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) { Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun NavRow(type: DeviceType, press: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SmallKey(RemoteKey.BACK, "Back", press, size = 64) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        SmallKey(RemoteKey.HOME, "Home", press, size = 64) { Icon(Icons.Default.Home, null) }
        if (type == DeviceType.ROKU) {
            SmallKey(RemoteKey.MENU, "Options", press, size = 64) { Text("✱", fontSize = 22.sp) }
        } else {
            SmallKey(RemoteKey.MENU, "Menu", press, size = 64) { Icon(Icons.Default.Menu, null) }
        }
    }
}

@Composable
private fun RockerRow(press: (RemoteKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rocker("VOL", RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, "Volume", press)
        SmallKey(RemoteKey.MUTE, "Mute", press, size = 60) { Icon(Icons.Default.VolumeOff, null) }
        Rocker("CH", RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN, "Channel", press)
    }
}

@Composable
private fun Rocker(label: String, up: RemoteKey, down: RemoteKey, name: String, press: (RemoteKey) -> Unit) {
    val shape = RoundedCornerShape(34.dp)
    Column(
        Modifier
            .width(68.dp)
            .height(164.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteButton({ press(up) }, "$name up", Modifier.fillMaxWidth().weight(1f), shape, repeat = true) {
            Icon(Icons.Default.Add, null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        RemoteButton({ press(down) }, "$name down", Modifier.fillMaxWidth().weight(1f), shape, repeat = true) {
            Icon(Icons.Default.Remove, null)
        }
    }
}

@Composable
private fun MediaRow(type: DeviceType, press: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (RemoteKey.REPLAY.supportedBy(type)) SmallKey(RemoteKey.REPLAY, "Instant replay", press) { Icon(Icons.Default.Replay, null) }
        SmallKey(RemoteKey.REWIND, "Rewind", press) { Icon(Icons.Default.FastRewind, null) }
        SmallKey(RemoteKey.PLAY_PAUSE, "Play or pause", press) {
            Row {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Icon(Icons.Default.Pause, null, Modifier.size(20.dp))
            }
        }
        SmallKey(RemoteKey.FAST_FORWARD, "Fast forward", press) { Icon(Icons.Default.FastForward, null) }
    }
}

// ---------- Apps / numbers / inputs ----------

@Composable
private fun Extras(
    type: DeviceType,
    apps: List<TvApp>,
    press: (RemoteKey) -> Unit,
    launchApp: (TvApp) -> Unit,
    launchPackage: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Apps", "Numbers", "Inputs")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> AppsPanel(type, apps, launchApp, launchPackage)
            1 -> KeyGrid(
                listOf(
                    RemoteKey.NUM_1 to "1", RemoteKey.NUM_2 to "2", RemoteKey.NUM_3 to "3",
                    RemoteKey.NUM_4 to "4", RemoteKey.NUM_5 to "5", RemoteKey.NUM_6 to "6",
                    RemoteKey.NUM_7 to "7", RemoteKey.NUM_8 to "8", RemoteKey.NUM_9 to "9",
                    RemoteKey.DEL to "⌫", RemoteKey.NUM_0 to "0", RemoteKey.ENTER to "Enter",
                ) + if (RemoteKey.GUIDE.supportedBy(type)) listOf(RemoteKey.GUIDE to "Guide") else emptyList(),
                press,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeyGrid(
                    listOf(
                        RemoteKey.INPUT_TV to "TV / Antenna", RemoteKey.INPUT_HDMI1 to "HDMI 1", RemoteKey.INPUT_HDMI2 to "HDMI 2",
                        RemoteKey.INPUT_HDMI3 to "HDMI 3", RemoteKey.INPUT_HDMI4 to "HDMI 4", RemoteKey.INPUT_AV to "AV",
                    ),
                    press,
                )
                Text(
                    "Switches the TV straight to that input. Some TV models don't support every input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun KeyGrid(keys: List<Pair<RemoteKey, String>>, press: (RemoteKey) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label) ->
                    RemoteButton(
                        onPress = { press(key) },
                        description = label,
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(label, fontSize = if (label.length <= 2) 22.sp else 15.sp) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppsPanel(type: DeviceType, apps: List<TvApp>, launchApp: (TvApp) -> Unit, launchPackage: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (apps.isEmpty()) {
            Text("No apps yet — connect to the TV first.", color = MaterialTheme.colorScheme.secondary)
        }
        apps.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { app ->
                    RemoteButton(
                        onPress = { launchApp(app) },
                        description = "Open ${app.name}",
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            app.name,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (type == DeviceType.ANDROID_TV) {
            var pkg by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it.trim() },
                    label = { Text("Open app by package name") },
                    placeholder = { Text("com.example.app") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = pkg.isNotEmpty(), onClick = { launchPackage(pkg) }) { Text("Open") }
            }
        }
    }
}

// ---------- Banners & dialogs ----------

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onPair: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) { Text("Reconnect") }
                if (onPair != null) OutlinedButton(onClick = onPair) { Text("Pair again") }
            }
        }
    }
}

@Composable
private fun PairingDialog(
    state: Connection.Pairing,
    deviceName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with $deviceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.codeShown) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Asking the TV to show a code…")
                    }
                } else {
                    Text("Type the 6-character code shown on your TV screen.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = if (state.error != null) { { Text(state.error) } } else null,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 26.sp, letterSpacing = 6.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit(code) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = state.codeShown && code.length == 6 && !state.busy, onClick = { onSubmit(code) }) {
                if (state.busy && state.codeShown) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Pair")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun KeyboardDialog(
    type: DeviceType,
    onSend: (String) -> Unit,
    onKey: (RemoteKey) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val send = {
        if (text.isNotEmpty()) {
            onSend(text)
            text = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (type == DeviceType.ANDROID_TV) "Select a text box on the TV first (for example YouTube search), then type here."
                    else "Open a search or text box on the TV, then type here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onKey(RemoteKey.DEL) }) { Text("⌫ Delete") }
                    OutlinedButton(onClick = { onKey(RemoteKey.ENTER) }) { Text("Enter ↵") }
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = send) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
