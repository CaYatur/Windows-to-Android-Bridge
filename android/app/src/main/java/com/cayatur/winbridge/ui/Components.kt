package com.cayatur.winbridge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * How to read a metric's value.
 *
 * [LOAD] is a usage figure where high is bad — CPU at 95% deserves red.
 * [LEVEL] is a reserve where *low* is bad, which is the opposite: a battery at
 * 95% is not a warning. Using one rule for both is why a full charging battery
 * was showing up red.
 */
enum class MetricTone { LOAD, LEVEL }

/** A labelled percentage bar. */
@Composable
fun MetricBar(
    label: String,
    percent: Double,
    detail: String? = null,
    tone: MetricTone = MetricTone.LOAD,
    charging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = (percent / 100.0).coerceIn(0.0, 1.0).toFloat(),
        label = label,
    )

    val red = Color(0xFFE5484D)
    val amber = Color(0xFFD29922)
    val green = Color(0xFF3FB950)

    val color = when {
        // Charging is a reassuring state at any level, so it always reads green.
        charging -> green
        tone == MetricTone.LEVEL -> when {
            percent <= 10 -> red
            percent <= 25 -> amber
            percent >= 80 -> green
            else -> MaterialTheme.colorScheme.primary
        }
        percent >= 90 -> red
        percent >= 70 -> amber
        else -> MaterialTheme.colorScheme.primary
    }

    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail ?: "${percent.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * The playback position, ticked forward locally between host pushes.
 *
 * The host sends a position only when the track or playback state changes, so
 * reading `posMs` straight from the last message makes the elapsed time sit
 * still and then jump. Advancing it from the moment the message arrived keeps
 * it moving; every new push re-syncs, so it can never drift far.
 */
@Composable
fun rememberLivePosition(media: com.cayatur.winbridge.protocol.MediaState): Long {
    val anchor = remember(media.title, media.posMs, media.playing) {
        media.posMs to android.os.SystemClock.elapsedRealtime()
    }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    LaunchedEffect(media.playing, media.title, media.posMs) {
        while (media.playing) {
            kotlinx.coroutines.delay(500)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }

    val (basePosition, baseTime) = anchor
    val elapsed = if (media.playing) (now - baseTime).coerceAtLeast(0) else 0
    return (basePosition + elapsed).coerceIn(0, if (media.durMs > 0) media.durMs else Long.MAX_VALUE)
}

fun formatGigabytes(megabytes: Long): String =
    String.format(Locale.US, "%.1f", megabytes / 1024.0)

fun formatBytesPerSecond(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
    bytesPerSecond >= 1_000 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_000.0)
    else -> "$bytesPerSecond B/s"
}

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
