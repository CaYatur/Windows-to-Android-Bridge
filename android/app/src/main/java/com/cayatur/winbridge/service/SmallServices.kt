package com.cayatur.winbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.ClipboardBridge
import com.cayatur.winbridge.feature.Notices
import com.cayatur.winbridge.net.ConnectionPhase
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.ui.ClipboardRelayActivity
import com.cayatur.winbridge.ui.PcScreenActivity
import kotlinx.coroutines.launch

/**
 * Handles taps on the notifications this app posts.
 *
 * A tap is a user gesture, which is why the clipboard action here works on
 * versions where a background write is refused: by the time this runs, the
 * system has already accepted that a person asked for it.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPLY_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                val clip = ClipboardBridge.build(text)

                if (!ClipboardBridge.applyDirect(context, clip)) {
                    ClipboardBridge.applyViaActivity(context, clip)
                }
                Notices.clear(context, Notices.ID_CLIPBOARD)
            }

            ACTION_STOP_CAPTURE -> CaptureService.stop(context)

            ACTION_STOP_RING -> WinBridgeApp.instance.ringer.stop()
        }
    }

    companion object {
        const val ACTION_APPLY_CLIPBOARD = "com.cayatur.winbridge.APPLY_CLIPBOARD"
        const val ACTION_STOP_CAPTURE = "com.cayatur.winbridge.STOP_CAPTURE"
        const val ACTION_STOP_RING = "com.cayatur.winbridge.STOP_RING"
        const val EXTRA_TEXT = "text"
    }
}

/**
 * "Clipboard to PC" in Quick Settings.
 *
 * This is the tier that always works. Reading the clipboard needs input focus,
 * and a tile tap is allowed to start an activity, so the relay gets its frame of
 * focus without the user having to open the app.
 */
class ClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val connected = WinBridgeApp.instance.state.connection.value.phase == ConnectionPhase.CONNECTED
        qsTile?.apply {
            state = if (connected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = getString(R.string.tile_clipboard)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardRelayActivity::class.java).apply {
            action = ClipboardRelayActivity.ACTION_SEND
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivityAndCollapseCompat(intent)
    }
}

/** "PC screen" in Quick Settings — opens the viewer straight from the shade. */
class MirrorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val connected = WinBridgeApp.instance.state.connection.value.phase == ConnectionPhase.CONNECTED
        qsTile?.apply {
            state = if (connected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = getString(R.string.tile_mirror)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        startActivityAndCollapseCompat(
            Intent(this, PcScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * startActivityAndCollapse took a PendingIntent from Android 14 and an Intent
 * before it, and the old overload now throws rather than being deprecated.
 */
private fun TileService.startActivityAndCollapseCompat(intent: Intent) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

/**
 * The documented way for another app — Tasker, MacroDroid, a Bixby routine, an
 * adb shell — to drive the bridge.
 *
 * There is no public on-device API that lets Gemini or Assistant call into a
 * third-party app with an arbitrary payload, so this is the substitute: a plain
 * broadcast, documented, and guarded by a token the user generates in settings.
 * An exported receiver with no secret would let any installed app run commands
 * on the PC, which is a considerably worse deal than the convenience is worth.
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = WinBridgeApp.instance

        if (!app.store.allowExternalTriggers) {
            Log.i(TAG, "external trigger ignored: turned off in settings")
            return
        }

        val expected = app.store.triggerToken
        val presented = intent.getStringExtra(EXTRA_TOKEN)
        if (expected.isNullOrBlank() || presented != expected) {
            Log.w(TAG, "external trigger refused: wrong or missing token")
            return
        }

        when (intent.action) {
            ACTION_RUN -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                app.scope.launch { app.client.sendMessage(AutoRunRequest(id = id)) }
                Toast.makeText(context, context.getString(R.string.automations_run), Toast.LENGTH_SHORT).show()
            }

            ACTION_CLIPBOARD -> ClipboardBridge.push(context) { clip ->
                app.scope.launch { app.client.sendMessage(clip) }
            }

            ACTION_COMMAND -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                app.voice.execute(text)
            }
        }
    }

    companion object {
        const val ACTION_RUN = "com.cayatur.winbridge.RUN_AUTOMATION"
        const val ACTION_CLIPBOARD = "com.cayatur.winbridge.SEND_CLIPBOARD"
        const val ACTION_COMMAND = "com.cayatur.winbridge.COMMAND"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }
}
