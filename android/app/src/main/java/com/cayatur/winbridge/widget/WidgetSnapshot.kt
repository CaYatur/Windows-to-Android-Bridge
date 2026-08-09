package com.cayatur.winbridge.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.ConnectionPhase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the widgets draw.
 *
 * Widgets are rendered by the launcher, potentially long after our process was
 * last alive, so they cannot read a StateFlow. A compact snapshot is written to
 * preferences whenever something changes and read back synchronously at render
 * time — which also means a widget shows the last known values instead of going
 * blank when the connection drops.
 */
@Serializable
data class WidgetSnapshot(
    val connected: Boolean = false,
    val hostName: String? = null,
    val carrier: String? = null,

    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val artHash: String? = null,

    val cpu: Int = 0,
    val gpu: Int = 0,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val netDownBps: Long = 0,
    val netUpBps: Long = 0,

    val batteryPresent: Boolean = false,
    val batteryPct: Int = 0,
    val batteryCharging: Boolean = false,
    val batteryStatus: String = "unknown",

    val volume: Int = 0,
    val muted: Boolean = false,

    val updatedAt: Long = 0,
)

object WidgetRepository {

    private const val PREFS = "winbridge.widget"
    private const val KEY = "snapshot"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(context: Context): WidgetSnapshot {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return WidgetSnapshot()
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrDefault(WidgetSnapshot())
    }

    private fun write(context: Context, snapshot: WidgetSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(snapshot))
            .apply()
    }

    fun snapshotOf(state: BridgeState): WidgetSnapshot {
        val connection = state.connection.value
        val media = state.media.value
        val system = state.system.value
        val volume = state.volume.value

        return WidgetSnapshot(
            connected = connection.phase == ConnectionPhase.CONNECTED,
            hostName = connection.hostName,
            carrier = connection.carrier?.name?.lowercase(),
            title = media?.title,
            artist = media?.artist,
            playing = media?.playing ?: false,
            artHash = media?.artHash,
            cpu = system?.cpu?.toInt() ?: 0,
            gpu = system?.gpu?.firstOrNull()?.pct?.toInt() ?: 0,
            ramUsedMb = system?.ram?.usedMb ?: 0,
            ramTotalMb = system?.ram?.totalMb ?: 0,
            netDownBps = system?.net?.downBps ?: 0,
            netUpBps = system?.net?.upBps ?: 0,
            batteryPresent = system?.battery?.present ?: false,
            batteryPct = system?.battery?.pct ?: 0,
            batteryCharging = system?.battery?.charging ?: false,
            batteryStatus = system?.battery?.status ?: "unknown",
            volume = volume?.level ?: 0,
            muted = volume?.muted ?: false,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Persists and repaints. Cheap enough to call on every state change. */
    suspend fun publish(context: Context, state: BridgeState) {
        write(context, snapshotOf(state))
        runCatching {
            MediaWidget().updateAll(context)
            SystemWidget().updateAll(context)
            CombinedWidget().updateAll(context)
            PowerWidget().updateAll(context)
        }
    }

    /** True when at least one widget is on a home screen. */
    suspend fun anyPlaced(context: Context): Boolean = runCatching {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(MediaWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(SystemWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(CombinedWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(PowerWidget::class.java).isNotEmpty()
    }.getOrDefault(false)
}
