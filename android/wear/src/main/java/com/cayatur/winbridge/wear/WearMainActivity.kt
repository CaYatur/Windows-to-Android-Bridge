package com.cayatur.winbridge.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.cayatur.winbridge.protocol.StateSnapshot
import kotlinx.coroutines.launch

/**
 * The watch app.
 *
 * Deliberately narrow: what is playing, how the PC is doing, and the few
 * controls that are safe to fire without a confirmation dialog on a screen this
 * size. Anything destructive stays on the phone.
 */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearState.load(this)
        setContent { MaterialTheme { WearRoot() } }
    }
}

@Composable
private fun WearRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by WearState.snapshot.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { WearState.refresh(context) }

    Box(Modifier.fillMaxSize()) {
        TimeText()

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberScalingLazyListState(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = if (snapshot.connected) {
                        snapshot.hostName ?: "PC"
                    } else {
                        context.getString(R.string.wear_offline)
                    },
                    style = MaterialTheme.typography.title3,
                    color = if (snapshot.connected) Color(0xFF3FB950) else Color(0xFFE5484D),
                )
            }

            item { MediaCard(snapshot) { command -> scope.launch { WearState.send(context, command) } } }
            item { SystemCard(snapshot) }

            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = { scope.launch { WearState.send(context, "power:lock") } },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(context.getString(R.string.wear_lock)) },
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = { scope.launch { WearState.send(context, "power:display_off") } },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(context.getString(R.string.wear_display_off)) },
                )
            }
        }
    }
}

@Composable
private fun MediaCard(snapshot: StateSnapshot, onCommand: (String) -> Unit) {
    val context = LocalContext.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val title = snapshot.title
        if (title.isNullOrBlank()) {
            Text(
                context.getString(R.string.wear_nothing_playing),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        Text(
            title,
            style = MaterialTheme.typography.body1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        snapshot.artist?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.caption2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportGlyph("⏮", enabled = snapshot.canPrev) { onCommand("media:prev") }
            Spacer(Modifier.width(14.dp))
            TransportGlyph(if (snapshot.playing) "⏸" else "▶") { onCommand("media:toggle") }
            Spacer(Modifier.width(14.dp))
            TransportGlyph("⏭", enabled = snapshot.canNext) { onCommand("media:next") }
        }
    }
}

@Composable
private fun TransportGlyph(glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        glyph,
        fontSize = 22.sp,
        color = if (enabled) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
        modifier = Modifier
            .size(40.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SystemCard(snapshot: StateSnapshot) {
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        MetricLine(context.getString(R.string.wear_cpu), "${snapshot.cpu}%", snapshot.cpu / 100f)
        MetricLine(context.getString(R.string.wear_gpu), "${snapshot.gpu}%", snapshot.gpu / 100f)

        val ramFraction =
            if (snapshot.ramTotalMb > 0) snapshot.ramUsedMb.toFloat() / snapshot.ramTotalMb else 0f
        MetricLine(
            context.getString(R.string.wear_ram),
            "${"%.1f".format(snapshot.ramUsedMb / 1024.0)} GB",
            ramFraction,
        )

        if (snapshot.batteryPresent) {
            MetricLine(
                context.getString(R.string.wear_battery),
                "${snapshot.batteryPct}%${if (snapshot.batteryCharging) " ⚡" else ""}",
                snapshot.batteryPct / 100f,
                level = true,
                charging = snapshot.batteryCharging,
            )
        }
    }
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
    fraction: Float,
    level: Boolean = false,
    charging: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.caption2)
            Text(value, style = MaterialTheme.typography.caption2)
        }
        Row(Modifier.fillMaxWidth().height(3.dp)) {
            val filled = fraction.coerceIn(0f, 1f)
            if (filled > 0f) {
                Box(
                    Modifier
                        .weight(filled)
                        .height(3.dp)
                        .background(barColor(filled, level, charging)),
                )
            }
            if (filled < 1f) {
                Box(
                    Modifier
                        .weight(1f - filled)
                        .height(3.dp)
                        .background(Color(0xFF33333B)),
                )
            }
        }
    }
}

/** Same rule as the phone: for a reserve, low is the bad end, not high. */
private fun barColor(fraction: Float, level: Boolean, charging: Boolean): Color = when {
    charging -> Color(0xFF3FB950)
    level -> when {
        fraction <= 0.10f -> Color(0xFFE5484D)
        fraction <= 0.25f -> Color(0xFFD29922)
        fraction >= 0.80f -> Color(0xFF3FB950)
        else -> Color(0xFF6E56CF)
    }
    fraction >= 0.9f -> Color(0xFFE5484D)
    fraction >= 0.7f -> Color(0xFFD29922)
    else -> Color(0xFF6E56CF)
}
