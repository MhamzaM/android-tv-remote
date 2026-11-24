package com.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvremote.data.DeviceType
import com.tvremote.data.TvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: RemoteViewModel) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TvDevice?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Remote", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = vm::scan) { Icon(Icons.Default.Refresh, "Search again") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add by IP") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (saved.isNotEmpty()) {
                item { SectionTitle("My TVs") }
                items(saved, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { vm.open(device) },
                        onRename = { renaming = device },
                        onForget = { vm.forget(device) },
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("On your Wi-Fi")
                    if (scanning) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (discovered.isEmpty()) {
                item {
                    Text(
                        if (scanning) "Searching for TVs…" else "No new TVs found. Tap ⟳ to search again, or add one by IP.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            items(discovered, key = { "found-" + it.id }) { device ->
                DeviceCard(device = device, onClick = { vm.open(device) })
            }
            item { HelpCard() }
        }
    }

    if (showAdd) {
        AddDeviceDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, ip, type ->
                showAdd = false
                vm.addManual(name, ip, type)
            },
        )
    }
    renaming?.let { device ->
        RenameDialog(device, onDismiss = { renaming = null }, onSave = { vm.rename(device, it); renaming = null })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    device: TvDevice,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onForget: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Tv, null, tint = Accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${device.type.label} · ${device.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (onForget != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename?.invoke() })
                        DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
                    }
                }
            } else {
                Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun HelpCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setting up a TCL TV", style = MaterialTheme.typography.titleSmall)
            HelpLine("Connect the phone and the TV to the same Wi-Fi network.")
            HelpLine(
                "TCL Google TV / Android TV: tap the TV, and a 6-character code appears on the TV screen. " +
                    "Type it in to pair (only needed once)."
            )
            HelpLine(
                "TCL Roku TV: on the TV open Settings › System › Advanced system settings › " +
                    "Control by mobile apps and allow network access."
            )
            HelpLine(
                "TV not found? Look up its IP address (Google TV: Settings › Network & Internet › your Wi-Fi; " +
                    "Roku: Settings › Network › About) and tap Add by IP."
            )
        }
    }
}

@Composable
private fun HelpLine(text: String) {
    Row {
        Text("•  ", color = Accent)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: (String, String, DeviceType?) -> Unit) {
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<DeviceType?>(null) }
    val options = listOf(
        null to "Detect automatically",
        DeviceType.ANDROID_TV to "Google TV / Android TV (TCL, Sony, Hisense…)",
        DeviceType.ROKU to "Roku TV (TCL Roku, Hisense Roku…)",
    )
    val valid = ip.trim().matches(Regex("^[A-Za-z0-9.-]+$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("Living room TCL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = type == value, role = Role.RadioButton, onClick = { type = value }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == value, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(name, ip, type) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(device: TvDevice, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename TV") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
