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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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

/** A labelled percentage bar. Colour shifts once the value gets uncomfortable. */
@Composable
fun MetricBar(
    label: String,
    percent: Double,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = (percent / 100.0).coerceIn(0.0, 1.0).toFloat(),
        label = label,
    )
    val color = when {
        percent >= 90 -> Color(0xFFE5484D)
        percent >= 70 -> Color(0xFFD29922)
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
