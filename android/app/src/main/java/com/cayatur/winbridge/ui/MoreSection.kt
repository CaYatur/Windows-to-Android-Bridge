package com.cayatur.winbridge.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.ClipboardBridge
import com.cayatur.winbridge.protocol.DescribeRequest
import com.cayatur.winbridge.service.NotificationRelay
import com.cayatur.winbridge.service.RemoteInputService
import kotlinx.coroutines.launch

/**
 * Everything that is neither a live reading nor an automation: the actions, the
 * feature switches, and the permissions those switches depend on.
 *
 * Permissions are shown next to the switch that needs them rather than in a
 * separate setup screen. A toggle that turns on but does nothing because an
 * Android permission is missing is the single most confusing thing this app
 * could do, so the state of the grant is on the same row as the switch.
 */
@Composable
fun MoreSection(onOpenScreen: () -> Unit, onVoice: () -> Unit) {
    val app = WinBridgeApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val features by app.state.hostFeatures.collectAsStateWithLifecycle()
    val transfers by app.files.transfers.collectAsStateWithLifecycle()
    val description by app.state.description.collectAsStateWithLifecycle()

    // Read once per composition rather than kept in state: these live in Android
    // settings, which the user may change while this screen is open, and there
    // is no callback for it.
    var refresh by remember { mutableIntStateOf(0) }
    val accessibilityOn = remember(refresh) { RemoteInputService.isEnabled() }
    val notificationsOn = remember(refresh) { NotificationRelay.isGranted(context) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {

        // ---- actions --------------------------------------------------------
        Text("Do something", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        FlowActions(
            onScreen = onOpenScreen,
            onClipboard = { ClipboardBridge.sendViaActivity(context) },
            onVoice = onVoice,
            onDescribe = {
                scope.launch { app.client.sendMessage(DescribeRequest(ocr = true)) }
            },
        )

        description?.let { answer ->
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(answer.title ?: "PC screen", fontWeight = FontWeight.SemiBold)
                    answer.text?.let {
                        Text(it.take(1200), style = MaterialTheme.typography.bodySmall)
                    }
                    answer.reason?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    if (app.store.speakAnswers) {
                        LaunchedEffect(answer) {
                            app.voice.speak(listOfNotNull(answer.title, answer.text?.take(400)).joinToString(". "))
                        }
                    }
                }
            }
        }

        // ---- transfers ------------------------------------------------------
        if (transfers.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.settings_files), fontWeight = FontWeight.SemiBold)
            transfers.takeLast(5).forEach { transfer ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(transfer.name, style = MaterialTheme.typography.bodyMedium)
                        val subtitle = transfer.error
                            ?: if (transfer.done) "done" else "${transfer.bytes / 1024} KB"
                        Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                    if (!transfer.done && transfer.total > 0) {
                        LinearProgressIndicator(
                            progress = { (transfer.bytes.toFloat() / transfer.total).coerceIn(0f, 1f) },
                            modifier = Modifier.width(90.dp),
                        )
                    }
                }
            }
        }

        // ---- switches -------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_features), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)

        Toggle(
            stringResource(R.string.settings_clipboard_to_pc),
            app.store.clipboardToPc,
        ) { app.store.clipboardToPc = it; app.announceFeatures(); refresh++ }

        Toggle(
            stringResource(R.string.settings_clipboard_from_pc),
            app.store.clipboardFromPc,
        ) { app.store.clipboardFromPc = it; app.announceFeatures(); refresh++ }

        Hint(stringResource(R.string.settings_clipboard_hint))

        Toggle(
            stringResource(R.string.settings_files),
            app.store.fileTransferEnabled,
        ) { app.store.fileTransferEnabled = it; app.announceFeatures(); refresh++ }

        Spacer(Modifier.height(10.dp))
        Toggle(
            stringResource(R.string.settings_screen_share),
            app.store.allowScreenShare,
        ) { app.store.allowScreenShare = it; app.announceFeatures(); refresh++ }
        Hint(stringResource(R.string.settings_screen_share_hint))

        Toggle(
            stringResource(R.string.settings_remote_input),
            app.store.allowRemoteInput,
        ) { app.store.allowRemoteInput = it; app.announceFeatures(); refresh++ }
        PermissionRow(
            granted = accessibilityOn,
            hint = stringResource(R.string.settings_remote_input_hint),
        ) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.settings_audio), fontWeight = FontWeight.SemiBold)
        Toggle(stringResource(R.string.settings_audio_from_pc), app.store.audioFromPc) {
            app.store.audioFromPc = it; app.announceFeatures(); refresh++
        }
        Toggle(stringResource(R.string.settings_mic_to_pc), app.store.micToPc) {
            app.store.micToPc = it; app.announceFeatures(); refresh++
        }
        Toggle(stringResource(R.string.settings_mic_from_pc), app.store.micFromPc) {
            app.store.micFromPc = it; app.announceFeatures(); refresh++
        }

        Spacer(Modifier.height(10.dp))
        Toggle(
            stringResource(R.string.settings_notifications),
            app.store.notificationMirror,
        ) { app.store.notificationMirror = it; app.announceFeatures(); refresh++ }
        PermissionRow(
            granted = notificationsOn,
            hint = stringResource(R.string.settings_notifications_hint),
        ) { context.startActivity(NotificationRelay.settingsIntent()) }

        // ---- assistant ------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_assistant), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)

        Toggle(stringResource(R.string.settings_shortcuts), app.store.publishShortcuts) {
            app.store.publishShortcuts = it; refresh++
        }
        Hint(stringResource(R.string.settings_shortcuts_hint))

        Toggle(stringResource(R.string.settings_speak), app.store.speakAnswers) {
            app.store.speakAnswers = it; refresh++
        }

        Toggle(stringResource(R.string.settings_triggers), app.store.allowExternalTriggers) {
            app.store.allowExternalTriggers = it; refresh++
        }
        if (app.store.allowExternalTriggers) {
            Text(
                stringResource(R.string.settings_trigger_token) + ": " + app.store.triggerToken.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            TextButton(onClick = {
                app.store.triggerToken = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                refresh++
            }) { Text(stringResource(R.string.settings_trigger_regenerate)) }
        }

        // ---- what the PC allows ---------------------------------------------
        features?.let { host ->
            Spacer(Modifier.height(18.dp))
            Text("Your PC allows", fontWeight = FontWeight.SemiBold)
            Hint(
                buildString {
                    append("clipboard ")
                    append(if (host.clipboard.send || host.clipboard.receive) "on" else "off")
                    append(" · files ").append(if (host.files.enabled) "on" else "off")
                    append(" · screen ").append(if (host.screen.send) "on" else "off")
                    append(" · input ").append(if (host.input.receive) "on" else "off")
                    append(" · automations ").append(if (host.automations) "on" else "off")
                    append(" · shell ").append(if (host.shell) "on" else "off")
                    if (!host.screen.carrierOk) append("\nMirroring needs the Wi-Fi link on this connection.")
                },
            )
        }
    }
}

@Composable
private fun FlowActions(
    onScreen: () -> Unit,
    onClipboard: () -> Unit,
    onVoice: () -> Unit,
    onDescribe: () -> Unit,
) {
    Column {
        Row {
            ActionButton(Icons.Filled.Monitor, stringResource(R.string.pc_screen), onScreen, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ActionButton(Icons.Filled.ContentPaste, stringResource(R.string.tile_clipboard), onClipboard, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            ActionButton(Icons.Filled.Mic, stringResource(R.string.voice_command), onVoice, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ActionButton(Icons.Filled.Visibility, "What is on screen", onDescribe, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(granted: Boolean, hint: String, onOpen: () -> Unit) {
    Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (granted) R.string.settings_granted else R.string.settings_not_granted),
                style = MaterialTheme.typography.labelMedium,
            )
            if (!granted) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.settings_open_android)) }
            }
        }
        Text(hint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}
