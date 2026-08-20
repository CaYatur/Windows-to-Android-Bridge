package com.cayatur.winbridge.wear

import android.content.Context
import android.util.Log
import com.cayatur.winbridge.protocol.StateSnapshot
import com.cayatur.winbridge.protocol.WearPaths
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

const val WEAR_TAG = "WinBridgeWear"

/**
 * The watch's view of the PC, relayed by the phone.
 *
 * Kept in a preference as well as in memory: a tile can be rendered by the
 * system when our process is not running, and the watch app should open showing
 * the last known state rather than a spinner.
 */
object WearState {

    private const val PREFS = "winbridge.wear"
    private const val KEY = "snapshot"

    private val _snapshot = MutableStateFlow(StateSnapshot())
    val snapshot: StateFlow<StateSnapshot> = _snapshot.asStateFlow()

    fun load(context: Context): StateSnapshot {
        val stored = StateSnapshot.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )
        _snapshot.value = stored
        return stored
    }

    fun store(context: Context, snapshot: StateSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, StateSnapshot.encode(snapshot))
            .apply()
        _snapshot.value = snapshot
    }

    /**
     * Pulls the current state directly, for when the app opens between pushes.
     * Data items are cached by the platform, so this works even if the phone is
     * momentarily unreachable.
     */
    suspend fun refresh(context: Context) {
        runCatching {
            val items = Wearable.getDataClient(context).dataItems.await()
            items.forEach { item ->
                if (item.uri.path == WearPaths.STATE) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val text = dataMap.getString(WearPaths.STATE_KEY)
                    store(context, StateSnapshot.decode(text))
                    WearArtwork.updateFromAsset(context, dataMap.getAsset(WearArtwork.DATA_KEY))
                }
            }
            items.release()
        }.onFailure { Log.d(WEAR_TAG, "refresh failed: ${it.message}") }
    }

    /** Sends a command to the phone, which relays it to the PC. */
    suspend fun send(context: Context, command: String) {
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w(WEAR_TAG, "no phone connected; command dropped")
                return
            }
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearPaths.COMMAND, command.toByteArray())
                    .await()
            }
        }.onFailure { Log.w(WEAR_TAG, "send failed: ${it.message}") }
    }
}

/** Receives state pushed by the phone, including while the app is closed. */
class WearStateService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

            when (event.dataItem.uri.path) {
                WearPaths.STATE -> {
                    WearState.store(
                        applicationContext,
                        StateSnapshot.decode(dataMap.getString(WearPaths.STATE_KEY)),
                    )
                    scope.launch {
                        WearArtwork.updateFromAsset(applicationContext, dataMap.getAsset(WearArtwork.DATA_KEY))
                        requestTileUpdates(withAutomations = false)
                    }
                }

                WearPaths.AUTOMATIONS -> {
                    WearExtras.storeAutomations(
                        applicationContext,
                        com.cayatur.winbridge.protocol.WearAutomations.decode(
                            dataMap.getString(WearPaths.AUTOMATIONS_KEY),
                        ),
                    )
                    scope.launch { requestTileUpdates(withAutomations = true) }
                }

                WearPaths.ANSWER ->
                    WearExtras.storeAnswer(applicationContext, dataMap.getString(WearPaths.ANSWER_KEY))
            }
        }
    }

    private fun requestTileUpdates(withAutomations: Boolean) {
        runCatching {
            val updater = androidx.wear.tiles.TileService.getUpdater(applicationContext)
            updater.requestUpdate(WinBridgeTileService::class.java)
            updater.requestUpdate(MediaTileService::class.java)
            // Only when the list actually moved: a tile refresh is not free, and
            // the automation tile does not change when a CPU reading does.
            if (withAutomations) updater.requestUpdate(AutomationTileService::class.java)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
