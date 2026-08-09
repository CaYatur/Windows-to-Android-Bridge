package com.cayatur.winbridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cayatur.winbridge.R
import com.cayatur.winbridge.net.CarrierKind
import com.cayatur.winbridge.net.ConnectionInfo
import com.cayatur.winbridge.net.ConnectionPhase
import com.cayatur.winbridge.protocol.HostState
import com.cayatur.winbridge.protocol.MediaState
import com.cayatur.winbridge.protocol.SystemState
import com.cayatur.winbridge.protocol.VolumeState

@Composable
fun ConnectionBanner(info: ConnectionInfo, paired: Boolean) {
    val (color, text) = when {
        !paired -> MaterialTheme.colorScheme.surfaceVariant to stringResource(R.string.status_not_paired)
        info.phase == ConnectionPhase.CONNECTED -> Color(0xFF1F6F3D) to
            stringResource(R.string.status_connected_to, info.hostName ?: "PC")
        info.phase == ConnectionPhase.CONNECTING -> Color(0xFF7A5E12) to
            stringResource(R.string.status_connecting)
        else -> Color(0xFF6E2427) to stringResource(R.string.status_disconnected)
    }

    val carrier = when (info.carrier) {
        CarrierKind.BLUETOOTH -> stringResource(R.string.status_over_bluetooth)
        CarrierKind.LAN -> stringResource(R.string.status_over_lan)
        null -> null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (info.carrier) {
                CarrierKind.BLUETOOTH -> Icons.Filled.Bluetooth
                CarrierKind.LAN -> Icons.Filled.Wifi
                null -> Icons.Filled.LinkOff
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            if (info.phase == ConnectionPhase.CONNECTED && carrier != null) {
                Text(
                    carrier,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun MediaSection(
    media: MediaState?,
    volume: VolumeState?,
    art: android.graphics.Bitmap?,
    onCommand: (String) -> Unit,
    onVolume: (Int) -> Unit,
    onMuteToggle: () -> Unit,
) {
    SectionCard(stringResource(R.string.media_title)) {
        if (media?.title.isNullOrBlank()) {
            Text(
                stringResource(R.string.media_nothing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(
                            art.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(Icons.Filled.MusicNote, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        media?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(media?.artist, media?.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if ((media?.durMs ?: 0) > 0) {
                // The host only pushes position when something actually changes,
                // so showing posMs verbatim makes the clock jump in long steps.
                // Ticking it forward locally between pushes is what makes it
                // read like a normal player.
                val livePosition = rememberLivePosition(media!!)
                val progress = (livePosition.toFloat() / media.durMs).coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDuration(livePosition), style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(media.durMs), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onCommand("prev") }, enabled = media?.canPrev == true) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.media_previous))
                }
                FilledIconButton(onClick = { onCommand("toggle") }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (media?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(R.string.media_playpause),
                    )
                }
                IconButton(onClick = { onCommand("next") }, enabled = media?.canNext == true) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.media_next))
                }
            }
        }

        // Volume belongs with media even when nothing is playing — it is the
        // control people reach for most often.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMuteToggle) {
                Icon(
                    if (volume?.muted == true) Icons.Filled.VolumeOff
                    else Icons.Filled.VolumeUp,
                    stringResource(R.string.media_volume),
                )
            }
            Slider(
                value = (volume?.level ?: 0).toFloat(),
                onValueChange = { onVolume(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${volume?.level ?: 0}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

@Composable
fun SystemSection(system: SystemState?, host: HostState?) {
    SectionCard(
        stringResource(R.string.system_title),
        trailing = {
            host?.let {
                Text(
                    stringResource(R.string.system_uptime, formatUptime(it.uptimeSec)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) {
        if (system == null) {
            Text(
                stringResource(R.string.system_waiting),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@SectionCard
        }

        MetricBar(stringResource(R.string.system_cpu), system.cpu)

        system.gpu.firstOrNull()?.let {
            MetricBar(stringResource(R.string.system_gpu), it.pct)
        }

        val ramPercent = if (system.ram.totalMb > 0) {
            system.ram.usedMb * 100.0 / system.ram.totalMb
        } else {
            0.0
        }
        MetricBar(
            stringResource(R.string.system_ram),
            ramPercent,
            detail = "${formatGigabytes(system.ram.usedMb)} / ${formatGigabytes(system.ram.totalMb)} GB",
        )

        KeyValue(
            stringResource(R.string.system_network),
            "↓ ${formatBytesPerSecond(system.net.downBps)}   ↑ ${formatBytesPerSecond(system.net.upBps)}",
        )

        system.disk.firstOrNull()?.let { disk ->
            val percent = if (disk.totalGb > 0) disk.usedGb * 100.0 / disk.totalGb else 0.0
            MetricBar(
                "${stringResource(R.string.system_disk)} ${disk.name}",
                percent,
                detail = "${disk.usedGb.toInt()} / ${disk.totalGb.toInt()} GB",
            )
        }

        if (system.battery.present) {
            val suffix = when (system.battery.status) {
                "charging" -> " · ${stringResource(R.string.system_battery_charging)}"
                "low" -> " · ${stringResource(R.string.system_battery_low)}"
                "critical" -> " · ${stringResource(R.string.system_battery_critical)}"
                "full" -> " · ${stringResource(R.string.system_battery_full)}"
                else -> ""
            }
            MetricBar(
                stringResource(R.string.system_battery),
                system.battery.pct.toDouble(),
                detail = "${system.battery.pct}%$suffix",
                tone = MetricTone.LEVEL,
                charging = system.battery.charging,
            )
        }
    }
}

private data class PowerAction(val key: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val supported: Boolean)

@Composable
fun ControlSection(host: HostState?, onPower: (String, String) -> Unit) {
    val caps = host?.caps
    val actions = listOf(
        PowerAction("lock", R.string.control_lock, Icons.Filled.Lock, caps?.lock ?: true),
        PowerAction("display_off", R.string.control_display_off, Icons.Filled.DesktopAccessDisabled, caps?.displayOff ?: true),
        PowerAction("sleep", R.string.control_sleep, Icons.Filled.Bedtime, caps?.sleep ?: false),
        PowerAction("hibernate", R.string.control_hibernate, Icons.Filled.AcUnit, caps?.hibernate ?: false),
        PowerAction("logoff", R.string.control_logoff, Icons.AutoMirrored.Filled.Logout, caps?.logoff ?: true),
        PowerAction("restart", R.string.control_restart, Icons.Filled.RestartAlt, caps?.restart ?: true),
        PowerAction("shutdown", R.string.control_shutdown, Icons.Filled.PowerSettingsNew, caps?.shutdown ?: true),
    )

    SectionCard(stringResource(R.string.control_title)) {
        Column(Modifier.padding(top = 8.dp)) {
            actions.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action ->
                        val label = stringResource(action.labelRes)
                        val unsupported = stringResource(R.string.control_unsupported)

                        // A button that silently does nothing is worse than one
                        // that is visibly unavailable, so capability drives state.
                        OutlinedButton(
                            onClick = { onPower(action.key, label) },
                            enabled = action.supported,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        ) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!action.supported) {
                            // Reserve the semantics even when disabled.
                            Spacer(Modifier.width(0.dp))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
