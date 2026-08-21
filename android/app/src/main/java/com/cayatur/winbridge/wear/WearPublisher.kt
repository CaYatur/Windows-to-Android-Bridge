package com.cayatur.winbridge.wear

import android.content.Context
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.BridgeState
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.protocol.AutomationSummary
import com.cayatur.winbridge.protocol.DescribeRequest
import com.cayatur.winbridge.protocol.InputKey
import com.cayatur.winbridge.protocol.InputMouse
import com.cayatur.winbridge.protocol.StateSnapshot
import com.cayatur.winbridge.protocol.WearAutomation
import com.cayatur.winbridge.protocol.WearAutomations
import com.cayatur.winbridge.protocol.WearCommands
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

    /**
     * Pushes the automation list on its own data item.
     *
     * Kept apart from the state snapshot because that one changes every second
     * while metrics tick; folding the list into it would resync an unchanged
     * list at 1 Hz, which is battery spent on nothing.
     */
    fun publishAutomations(context: Context, items: List<AutomationSummary>, shellEnabled: Boolean) {
        runCatching {
            val payload = WearAutomations(
                items = items
                    .filter { it.enabled && it.approved }
                    .take(12)
                    .map { WearAutomation(it.id, it.name, it.risk, it.confirmEachRun) },
                shellEnabled = shellEnabled,
                updatedAt = System.currentTimeMillis(),
            )

            val request = PutDataMapRequest.create(WearPaths.AUTOMATIONS).apply {
                dataMap.putString(WearPaths.AUTOMATIONS_KEY, WearAutomations.encode(payload))
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
        }.onFailure { Log.d(TAG, "wear automations skipped: ${it.message}") }
    }

    /** Sends a line of text for the watch to show, and read aloud if it can. */
    fun publishAnswer(context: Context, text: String) {
        runCatching {
            val request = PutDataMapRequest.create(WearPaths.ANSWER).apply {
                dataMap.putString(WearPaths.ANSWER_KEY, text.take(600))
                dataMap.putLong("ts", System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
        }.onFailure { Log.d(TAG, "wear answer skipped: ${it.message}") }
    }
}

/**
 * Receives commands sent from the watch.
 *
 * The watch is treated as a less-trusted surface than the phone: it has a
 * two-centimetre screen and no way to show a command line, so the destructive
 * power actions are not offered here at all, and an automation the PC marked as
 * needing confirmation still gets confirmed — on the PC, where there is room to
 * read what it does.
 */
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
                WearCommands.MEDIA -> app.client.mediaCommand(action)
                WearCommands.VOLUME -> volume(app, action)

                // The watch offers only the reversible power actions; anything
                // destructive stays behind a confirmation on the phone.
                WearCommands.POWER ->
                    if (action in setOf("lock", "display_off")) app.client.powerCommand(action)

                WearCommands.AUTOMATION -> {
                    if (app.store.automationsEnabled) app.client.sendMessage(AutoRunRequest(id = action))
                }

                WearCommands.VOICE -> {
                    val outcome = app.voice.execute(action)
                    WearPublisher.publishAnswer(
                        applicationContext,
                        if (outcome.understood) outcome.description
                        else getString(com.cayatur.winbridge.R.string.voice_no_match),
                    )
                }

                WearCommands.MOUSE -> mouse(app, action)
                WearCommands.KEY -> key(app, action)

                WearCommands.CLIPBOARD ->
                    com.cayatur.winbridge.feature.ClipboardBridge.push(applicationContext) { clip ->
                        app.launch { app.client.sendMessage(clip) }
                    }

                WearCommands.DESCRIBE -> app.client.sendMessage(DescribeRequest(ocr = true))

                WearCommands.SYNC -> app.client.requestFullState()
            }
        }
    }

    private suspend fun volume(app: WinBridgeApp, action: String) {
        // "up" and "down" are resolved here rather than on the watch, because
        // only this side knows the current level.
        when (action) {
            "up", "down" -> {
                val current = app.state.volume.value?.level ?: 50
                val step = if (action == "up") 5 else -5
                app.client.volumeCommand("set", (current + step).coerceIn(0, 100))
            }
            else -> app.client.volumeCommand(action)
        }
    }

    private suspend fun mouse(app: WinBridgeApp, action: String) {
        val parts = action.split(':')
        when (parts.firstOrNull()) {
            "move" -> {
                val deltas = parts.getOrNull(1)?.split(',') ?: return
                val dx = deltas.getOrNull(0)?.toDoubleOrNull() ?: return
                val dy = deltas.getOrNull(1)?.toDoubleOrNull() ?: return
                app.client.sendMessage(InputMouse(action = "move", dx = dx, dy = dy, relative = true))
            }
            "click" -> app.client.sendMessage(
                InputMouse(action = "click", button = parts.getOrNull(1) ?: "left"),
            )
            "wheel" -> app.client.sendMessage(
                InputMouse(action = "wheel", delta = parts.getOrNull(1)?.toIntOrNull() ?: 0),
            )
        }
    }

    private suspend fun key(app: WinBridgeApp, action: String) {
        val parts = action.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        app.client.sendMessage(InputKey(code = parts.first(), mods = parts.drop(1)))
    }
}
