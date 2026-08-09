package com.cayatur.winbridge.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.StateSnapshot

private val CommandKey = ActionParameters.Key<String>("command")

class MediaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[CommandKey] ?: return
        WinBridgeApp.instance.client.mediaCommand(action)
    }
}

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
            .cornerRadius(18.dp)
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun ArtworkWidgetFrame(artwork: Bitmap?, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(18.dp)) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(androidx.glance.unit.ColorProvider(Color(0xD9181820)))
                .padding(12.dp),
        ) { content() }
    }
}

@Composable
private fun OfflineNotice(context: Context) {
    Text(
        context.getString(R.string.widget_offline),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

@Composable
private fun HostHeader(snapshot: StateSnapshot) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            snapshot.hostName ?: "WinBridge",
            maxLines = 1,
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

@Composable
private fun MetricRow(
    label: String,
    value: String,
    fraction: Float,
    level: Boolean = false,
    charging: Boolean = false,
) {
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
        val filled = fraction.coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = filled,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
            color = barColor(filled, level, charging),
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }
}

private fun barColor(
    fraction: Float,
    level: Boolean,
    charging: Boolean,
): androidx.glance.unit.ColorProvider = androidx.glance.unit.ColorProvider(
    when {
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
    },
)

@Composable
private fun MediaBlock(context: Context, snapshot: StateSnapshot, compact: Boolean) {
    val title = snapshot.title
    if (title.isNullOrBlank()) {
        Text(
            context.getString(R.string.media_nothing),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
        return
    }

    Text(
        title,
        maxLines = if (compact) 1 else 2,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = if (compact) 13.sp else 16.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    snapshot.artist?.takeIf { it.isNotBlank() }?.let {
        Text(
            it,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }

    if (snapshot.durMs > 0) {
        val progress = (snapshot.posMs.toFloat() / snapshot.durMs).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = progress,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp).padding(top = 8.dp),
            color = GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }

    Row(
        GlanceModifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(R.drawable.ic_media_previous, "prev")
        Spacer(GlanceModifier.width(10.dp))
        TransportButton(
            if (snapshot.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            "toggle",
            emphasized = true,
        )
        Spacer(GlanceModifier.width(10.dp))
        TransportButton(R.drawable.ic_media_next, "next")
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (snapshot.playing) "PLAYING" else "PAUSED",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun TransportButton(@DrawableRes iconRes: Int, command: String, emphasized: Boolean = false) {
    Image(
        provider = ImageProvider(iconRes),
        contentDescription = null,
        modifier = GlanceModifier
            .size(if (emphasized) 40.dp else 34.dp)
            .background(if (emphasized) GlanceTheme.colors.primary else GlanceTheme.colors.primaryContainer)
            .cornerRadius(if (emphasized) 20.dp else 17.dp)
            .padding(if (emphasized) 9.dp else 7.dp)
            .clickable(actionRunCallback<MediaAction>(actionParametersOf(CommandKey to command))),
    )
}

@Composable
private fun SystemBlock(context: Context, snapshot: StateSnapshot) {
    MetricRow(context.getString(R.string.system_cpu), "${snapshot.cpu}%", snapshot.cpu / 100f)
    MetricRow(context.getString(R.string.system_gpu), "${snapshot.gpu}%", snapshot.gpu / 100f)

    val ramFraction = if (snapshot.ramTotalMb > 0) snapshot.ramUsedMb.toFloat() / snapshot.ramTotalMb else 0f
    MetricRow(
        context.getString(R.string.system_ram),
        "${"%.1f".format(snapshot.ramUsedMb / 1024.0)} GB",
        ramFraction,
    )

    if (snapshot.batteryPresent) {
        MetricRow(
            context.getString(R.string.system_battery),
            "${snapshot.batteryPct}%${if (snapshot.batteryCharging) " · CHG" else ""}",
            snapshot.batteryPct / 100f,
            level = true,
            charging = snapshot.batteryCharging,
        )
    }
}

class MediaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetRepository.read(context)
        val artwork = snapshot.artHash?.let { WinBridgeApp.instance.state.loadArt(it) }
        provideContent {
            GlanceTheme {
                ArtworkWidgetFrame(artwork) {
                    HostHeader(snapshot)
                    Spacer(GlanceModifier.height(8.dp))
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
        val artwork = snapshot.artHash?.let { WinBridgeApp.instance.state.loadArt(it) }
        provideContent {
            GlanceTheme {
                ArtworkWidgetFrame(artwork) {
                    HostHeader(snapshot)
                    if (!snapshot.connected) {
                        OfflineNotice(context)
                    } else {
                        Spacer(GlanceModifier.height(6.dp))
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
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
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
