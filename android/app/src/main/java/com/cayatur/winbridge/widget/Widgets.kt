package com.cayatur.winbridge.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import kotlinx.coroutines.runBlocking

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

private val CommandKey = ActionParameters.Key<String>("command")

/** Media transport buttons. Fire and forget — the state push confirms them. */
class MediaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[CommandKey] ?: return
        WinBridgeApp.instance.client.mediaCommand(action)
    }
}

/**
 * Power from a widget is deliberately limited to Lock.
 *
 * A home screen has no confirmation step, and an accidental long-press landing
 * on "Shut down" is not a recoverable mistake. Everything destructive stays in
 * the app, behind a dialog.
 */
class PowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[CommandKey] ?: return
        if (action !in setOf("lock", "display_off")) return
        WinBridgeApp.instance.client.powerCommand(action)
    }
}

@Composable
private fun WidgetFrame(content: @Composable () -> Unit) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun OfflineNotice(context: Context) {
    Text(
        context.getString(R.string.widget_offline),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

@Composable
private fun MetricRow(label: String, value: String, fraction: Float) {
    Column(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(label, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                value,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        // Glance has no progress bar, so the bar is two coloured boxes.
        Row(GlanceModifier.fillMaxWidth().height(4.dp).padding(top = 2.dp)) {
            val filled = fraction.coerceIn(0f, 1f)
            if (filled > 0f) {
                Spacer(
                    GlanceModifier
                        .defaultWeight()
                        .height(4.dp)
                        .cornerRadius(2.dp)
                        .background(barColor(filled)),
                )
            }
            if (filled < 1f) {
                Spacer(
                    GlanceModifier
                        .defaultWeight()
                        .height(4.dp)
                        .cornerRadius(2.dp)
                        .background(GlanceTheme.colors.surfaceVariant),
                )
            }
        }
    }
}

private fun barColor(fraction: Float): androidx.glance.unit.ColorProvider =
    androidx.glance.unit.ColorProvider(
        when {
            fraction >= 0.9f -> Color(0xFFE5484D)
            fraction >= 0.7f -> Color(0xFFD29922)
            else -> Color(0xFF6E56CF)
        },
    )

@Composable
private fun MediaBlock(context: Context, snapshot: WidgetSnapshot, compact: Boolean) {
    if (snapshot.title.isNullOrBlank()) {
        Text(
            context.getString(R.string.media_nothing),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
        return
    }

    Text(
        snapshot.title,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
    snapshot.artist?.takeIf { it.isNotBlank() }?.let {
        Text(
            it,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }

    Row(
        GlanceModifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton("⏮", "prev")
        Spacer(GlanceModifier.width(10.dp))
        TransportButton(if (snapshot.playing) "⏸" else "▶", "toggle")
        Spacer(GlanceModifier.width(10.dp))
        TransportButton("⏭", "next")
    }
}

@Composable
private fun TransportButton(glyph: String, command: String) {
    Text(
        glyph,
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 20.sp),
        modifier = GlanceModifier
            .clickable(actionRunCallback<MediaAction>(actionParametersOf(CommandKey to command)))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SystemBlock(context: Context, snapshot: WidgetSnapshot) {
    MetricRow(context.getString(R.string.system_cpu), "${snapshot.cpu}%", snapshot.cpu / 100f)
    MetricRow(context.getString(R.string.system_gpu), "${snapshot.gpu}%", snapshot.gpu / 100f)

    val ramFraction =
        if (snapshot.ramTotalMb > 0) snapshot.ramUsedMb.toFloat() / snapshot.ramTotalMb else 0f
    MetricRow(
        context.getString(R.string.system_ram),
        "${"%.1f".format(snapshot.ramUsedMb / 1024.0)} GB",
        ramFraction,
    )

    if (snapshot.batteryPresent) {
        val suffix = if (snapshot.batteryCharging) " ⚡" else ""
        MetricRow(
            context.getString(R.string.system_battery),
            "${snapshot.batteryPct}%$suffix",
            snapshot.batteryPct / 100f,
        )
    }
}

@Composable
private fun HostHeader(snapshot: WidgetSnapshot) {
    Row(GlanceModifier.fillMaxWidth()) {
        Text(
            snapshot.hostName ?: "WinBridge",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (snapshot.carrier == "bluetooth") "BT" else "Wi-Fi",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}

// ---------------------------------------------------------------------------
// The four widgets
// ---------------------------------------------------------------------------

class MediaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetRepository.read(context)
        provideContent {
            GlanceTheme {
                WidgetFrame {
                    if (!snapshot.connected) OfflineNotice(context)
                    else MediaBlock(context, snapshot, compact = false)
                }
            }
        }
    }
}

class SystemWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetRepository.read(context)
        provideContent {
            GlanceTheme {
                WidgetFrame {
                    HostHeader(snapshot)
                    if (!snapshot.connected) OfflineNotice(context)
                    else SystemBlock(context, snapshot)
                }
            }
        }
    }
}

class CombinedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetRepository.read(context)
        provideContent {
            GlanceTheme {
                WidgetFrame {
                    HostHeader(snapshot)
                    if (!snapshot.connected) {
                        OfflineNotice(context)
                    } else {
                        MediaBlock(context, snapshot, compact = true)
                        Spacer(GlanceModifier.height(6.dp))
                        SystemBlock(context, snapshot)
                    }
                }
            }
        }
    }
}

class PowerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetRepository.read(context)
        provideContent {
            GlanceTheme {
                WidgetFrame {
                    HostHeader(snapshot)
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        PowerButton(context.getString(R.string.control_lock), "lock")
                        Spacer(GlanceModifier.width(8.dp))
                        PowerButton(context.getString(R.string.control_display_off), "display_off")
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerButton(label: String, command: String) {
    Text(
        label,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(10.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(actionRunCallback<PowerAction>(actionParametersOf(CommandKey to command))),
    )
}

class MediaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = MediaWidget()
}

class SystemWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = SystemWidget()
}

class CombinedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = CombinedWidget()
}

class PowerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = PowerWidget()
}
