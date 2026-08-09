package com.cayatur.winbridge.wear

import android.content.Context
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.StateSnapshot
import com.cayatur.winbridge.protocol.WearPaths
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Pushes state to a paired watch and takes commands back from it.
 *
 * The watch never talks to the PC. It talks to the phone, and the phone relays
 * — which is why the watch app needs the phone app installed, and why the watch
 * keeps working when it is out of Bluetooth range of the PC but still near the
 * phone.
 */
object WearPublisher {

    /**
     * Data items are synced and cached by the platform, so a watch that was
     * off or out of range gets the latest state as soon as it comes back
     * instead of showing nothing until the next change.
     */
    fun publish(context: Context, state: BridgeState, snapshot: StateSnapshot) {
        runCatching {
            val request = PutDataMapRequest.create(WearPaths.STATE).apply {
                dataMap.putString(WearPaths.STATE_KEY, StateSnapshot.encode(snapshot))
                snapshot.artHash?.let { hash ->
                    state.loadArt(hash)?.let { bitmap ->
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, stream)
                        dataMap.putAsset("artwork", Asset.createFromBytes(stream.toByteArray()))
                    }
                }
                // Data items are deduplicated by content; without something that
                // always changes, an identical snapshot would not resync.
                dataMap.putLong("ts", System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
        }.onFailure {
            Log.d(TAG, "wear publish skipped: ${it.message}")
        }
    }
}

/** Receives transport and power commands sent from the watch. */
class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.COMMAND) return

        val command = event.data.decodeToString()
        Log.i(TAG, "wear command: $command")

        val app = applicationContext as? WinBridgeApp ?: return
        val (kind, action) = command.split(':', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return
        }

        runBlocking {
            when (kind) {
                "media" -> app.client.mediaCommand(action)
                "volume" -> app.client.volumeCommand(action)
                // The watch offers only the reversible power actions; anything
                // destructive stays behind a confirmation on the phone.
                "power" -> if (action in setOf("lock", "display_off")) app.client.powerCommand(action)
            }
        }
    }
}
