package com.cayatur.winbridge.wear

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.cayatur.winbridge.protocol.StateSnapshot
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearState.load(this)
        WearArtwork.load(this)
        setContent { MaterialTheme { WearRoot() } }
    }
}

@Composable
private fun WearRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by WearState.snapshot.collectAsStateWithLifecycle()
    val artwork by WearArtwork.bitmap.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { WearState.refresh(context) }

    Box(Modifier.fillMaxSize()) {
        TimeText()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberScalingLazyListState(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ConnectionHeader(snapshot)
            }
            item {
                MediaCard(snapshot, artwork) { command ->
                    scope.launch { WearState.send(context, command) }
                }
            }
            item { SystemCard(snapshot) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = { scope.launch { WearState.send(context, "power:lock") } },
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text(context.getString(R.string.wear_lock)) },
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = { scope.launch { WearState.send(context, "power:display_off") } },
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_display_off),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text(context.getString(R.string.wear_display_off)) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionHeader(snapshot: StateSnapshot) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (snapshot.connected) snapshot.hostName ?: "PC" else context.getString(R.string.wear_offline),
            style = MaterialTheme.typography.title3,
            color = if (snapshot.connected) Color(0xFF67D47E) else Color(0xFFFF6B70),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (snapshot.connected) {
            Text(
                text = context.getString(R.string.wear_watch_control),
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaCard(snapshot: StateSnapshot, artwork: Bitmap?, onCommand: (String) -> Unit) {
    val context = LocalContext.current
    val title = snapshot.title

    Box(
        Modifier
            .fillMaxWidth()
            .height(188.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xC914141A)))

        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (snapshot.playing) context.getString(R.string.wear_playing)
                else context.getString(R.string.wear_paused),
                color = if (snapshot.playing) Color(0xFF67D47E) else Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))

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
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            snapshot.artist?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            if (snapshot.durMs > 0) {
                ProgressLine(snapshot.posMs.toFloat() / snapshot.durMs)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(R.drawable.ic_media_previous, snapshot.canPrev) { onCommand("media:prev") }
                Spacer(Modifier.width(10.dp))
                TransportButton(
                    if (snapshot.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    true,
                    emphasized = true,
                ) { onCommand("media:toggle") }
                Spacer(Modifier.width(10.dp))
                TransportButton(R.drawable.ic_media_next, snapshot.canNext) { onCommand("media:next") }
            }
        }
    }
}

@Composable
private fun TransportButton(
    drawable: Int,
    enabled: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (emphasized) 44.dp else 38.dp),
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = null,
            modifier = Modifier.size(if (emphasized) 22.dp else 18.dp),
        )
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        val filled = fraction.coerceIn(0f, 1f)
        if (filled > 0f) Box(Modifier.weight(filled).fillMaxSize().background(Color(0xFF8C7DFF)))
        if (filled < 1f) Box(Modifier.weight(1f - filled).fillMaxSize().background(Color.White.copy(alpha = 0.2f)))
    }
}

@Composable
private fun SystemCard(snapshot: StateSnapshot) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF202027))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        MetricLine(context.getString(R.string.wear_cpu), "${snapshot.cpu}%", snapshot.cpu / 100f)
        MetricLine(context.getString(R.string.wear_gpu), "${snapshot.gpu}%", snapshot.gpu / 100f)

        val ramFraction = if (snapshot.ramTotalMb > 0) snapshot.ramUsedMb.toFloat() / snapshot.ramTotalMb else 0f
        MetricLine(
            context.getString(R.string.wear_ram),
            "${"%.1f".format(snapshot.ramUsedMb / 1024.0)} GB",
            ramFraction,
        )

        if (snapshot.batteryPresent) {
            val batteryText = if (snapshot.batteryCharging) {
                "${snapshot.batteryPct}% · ${context.getString(R.string.wear_charging)}"
            } else {
                "${snapshot.batteryPct}%"
            }
            MetricLine(
                context.getString(R.string.wear_battery),
                batteryText,
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
    Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.caption2, color = Color.White.copy(alpha = 0.72f))
            Text(value, style = MaterialTheme.typography.caption2, maxLines = 1)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
        ) {
            val filled = fraction.coerceIn(0f, 1f)
            if (filled > 0f) Box(Modifier.weight(filled).fillMaxSize().background(barColor(filled, level, charging)))
            if (filled < 1f) Box(Modifier.weight(1f - filled).fillMaxSize().background(Color(0xFF393941)))
        }
    }
}

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
