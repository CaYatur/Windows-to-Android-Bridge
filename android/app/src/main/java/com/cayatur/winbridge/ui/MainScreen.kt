package com.cayatur.winbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import kotlinx.coroutines.launch

private enum class Tab(val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Filled.Dashboard),
    MEDIA(R.string.tab_media, Icons.Filled.MusicNote),
    SYSTEM(R.string.tab_system, Icons.Filled.Memory),
    CONTROL(R.string.tab_control, Icons.Filled.PowerSettingsNew),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onPair: () -> Unit) {
    val app = WinBridgeApp.instance
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    var pendingPower by remember { mutableStateOf<Pair<String, String>?>(null) }

    val connection by app.state.connection.collectAsStateWithLifecycle()
    val host by app.state.host.collectAsStateWithLifecycle()
    val media by app.state.media.collectAsStateWithLifecycle()
    val system by app.state.system.collectAsStateWithLifecycle()
    val volume by app.state.volume.collectAsStateWithLifecycle()
    val art by app.state.art.collectAsStateWithLifecycle()

    // A screen is open, so ask for 1 Hz; the service drops back to the widget
    // rate when this leaves composition.
    DisposableEffect(Unit) {
        scope.launch { app.client.setSystemRate(1000) }
        onDispose { scope.launch { app.client.setSystemRate(5_000) } }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        app.state.errors.collect { error ->
            snackbar.showSnackbar(error.detail ?: error.code)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ConnectionBanner(connection, app.store.isPaired)

            val mediaSection = @Composable {
                MediaSection(
                    media = media,
                    volume = volume,
                    art = art?.second,
                    onCommand = { action -> scope.launch { app.client.mediaCommand(action) } },
                    onVolume = { level -> scope.launch { app.client.volumeCommand("set", level) } },
                    onMuteToggle = {
                        scope.launch {
                            app.client.volumeCommand(if (volume?.muted == true) "unmute" else "mute")
                        }
                    },
                )
            }
            val systemSection = @Composable { SystemSection(system, host) }
            val controlSection = @Composable {
                ControlSection(host) { action, label -> pendingPower = action to label }
            }

            when (tab) {
                Tab.OVERVIEW -> {
                    mediaSection()
                    systemSection()
                    controlSection()
                }
                Tab.MEDIA -> mediaSection()
                Tab.SYSTEM -> systemSection()
                Tab.CONTROL -> controlSection()
                Tab.SETTINGS -> SettingsSection(onPair = onPair)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Power actions are irreversible from the phone's point of view, so each
    // one is confirmed rather than fired on a single tap.
    pendingPower?.let { (action, label) ->
        AlertDialog(
            onDismissRequest = { pendingPower = null },
            title = { Text(stringResource(R.string.control_confirm_title, label)) },
            text = { Text(stringResource(R.string.control_confirm_body, label.lowercase())) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.client.powerCommand(action) }
                    pendingPower = null
                }) { Text(stringResource(R.string.control_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPower = null }) {
                    Text(stringResource(R.string.control_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(onPair: () -> Unit) {
    val app = WinBridgeApp.instance
    val scope = rememberCoroutineScope()

    var preferBluetooth by remember { mutableStateOf(app.store.preferBluetooth) }
    var paired by remember { mutableStateOf(app.store.isPaired) }
    var confirmForget by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.settings_paired_pc)) {
        if (!paired) {
            Text(
                stringResource(R.string.status_not_paired),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(onClick = onPair, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.setup_scan))
            }
        } else {
            KeyValue(stringResource(R.string.app_name), app.store.hostName ?: "—")
            app.store.hostBtMac?.let { KeyValue(stringResource(R.string.settings_bluetooth_address), it) }
            app.store.hostLanHosts.firstOrNull()?.let {
                KeyValue(stringResource(R.string.settings_lan_address), "$it:${app.store.hostLanPort}")
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.client.wake() }) {
                    Text(stringResource(R.string.settings_reconnect))
                }
                OutlinedButton(onClick = { confirmForget = true }) {
                    Text(stringResource(R.string.settings_forget))
                }
            }
        }
    }

    var mediaNotification by remember { mutableStateOf(app.store.showMediaNotification) }

    SectionCard(stringResource(R.string.media_title)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_media_notification),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_media_notification_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = mediaNotification,
                onCheckedChange = {
                    mediaNotification = it
                    app.store.showMediaNotification = it
                    // Take effect now rather than at the next track change.
                    scope.launch { app.client.requestFullState() }
                },
            )
        }
    }

    SectionCard(stringResource(R.string.settings_prefer_bluetooth)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_prefer_bluetooth_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = preferBluetooth,
                onCheckedChange = {
                    preferBluetooth = it
                    app.store.preferBluetooth = it
                    app.client.wake()
                },
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_forget)) },
            text = {
                Text(stringResource(R.string.settings_forget_confirm, app.store.hostName ?: "PC"))
            },
            confirmButton = {
                TextButton(onClick = {
                    app.store.forgetHost()
                    paired = false
                    confirmForget = false
                    scope.launch { app.client.wake() }
                }) { Text(stringResource(R.string.control_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.control_cancel))
                }
            },
        )
    }
}
