package com.cayatur.winbridge.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.ConnectionPhase
import com.cayatur.winbridge.protocol.StateSnapshot
import com.cayatur.winbridge.wear.WearPublisher

/**
 * Persists the glanceable state and repaints everything that renders it.
 *
 * Widgets are drawn by the launcher, potentially long after our process was
 * last alive, so they cannot read a StateFlow. A snapshot is written to
 * preferences on every change and read back synchronously at render time —
 * which also means a widget shows the last known values instead of going blank
 * when the connection drops.
 */
object WidgetRepository {

    private const val PREFS = "winbridge.widget"
    private const val KEY = "snapshot"

    fun read(context: Context): StateSnapshot =
        StateSnapshot.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )

    private fun write(context: Context, snapshot: StateSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, StateSnapshot.encode(snapshot))
            .apply()
    }

    fun snapshotOf(state: BridgeState): StateSnapshot {
        val connection = state.connection.value
        val media = state.media.value
        val system = state.system.value
        val volume = state.volume.value

        return StateSnapshot(
            connected = connection.phase == ConnectionPhase.CONNECTED,
            hostName = connection.hostName,
            carrier = connection.carrier?.name?.lowercase(),
            title = media?.title,
            artist = media?.artist,
            playing = media?.playing ?: false,
            artHash = media?.artHash,
            posMs = media?.posMs ?: 0,
            durMs = media?.durMs ?: 0,
            canNext = media?.canNext ?: false,
            canPrev = media?.canPrev ?: false,
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

    /** Persists, repaints the widgets, and forwards to a paired watch. */
    suspend fun publish(context: Context, state: BridgeState) {
        val snapshot = snapshotOf(state)
        write(context, snapshot)

        runCatching {
            MediaWidget().updateAll(context)
            SystemWidget().updateAll(context)
            CombinedWidget().updateAll(context)
            PowerWidget().updateAll(context)
        }

        WearPublisher.publish(context, state, snapshot)
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
