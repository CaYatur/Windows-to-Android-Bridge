package com.cayatur.winbridge.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.ClipboardBridge
import kotlinx.coroutines.launch

/**
 * Exists for a few frames so that the clipboard can be touched.
 *
 * Android hands clipboard access to whichever app has input focus. A foreground
 * service does not have focus; an Activity does. This one is transparent, has
 * its window animation disabled and finishes inside onCreate, so what the user
 * sees is nothing at all — which is the difference between a working feature and
 * a screen that flashes every time the PC copies a word.
 *
 * It is also the target of the "Clipboard to PC" shortcut and the Quick Settings
 * tile, both of which are user gestures and therefore always allowed.
 */
class ClipboardRelayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = WinBridgeApp.instance
        when (intent?.action) {
            ACTION_APPLY -> apply()
            else -> send(app)
        }

        // No animation and no lingering task: this window should never appear in
        // recents, and it must not still be around on the next launch.
        finish()
        overridePendingTransition(0, 0)
    }

    private fun apply() {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val clip = ClipboardBridge.build(text).copy(
            label = intent.getStringExtra(EXTRA_LABEL),
            hash = intent.getStringExtra(EXTRA_HASH),
        )

        if (ClipboardBridge.applyDirect(this, clip)) {
            ClipboardBridge.remember(clip)
        } else {
            Toast.makeText(this, R.string.clipboard_blocked, Toast.LENGTH_SHORT).show()
        }
    }

    private fun send(app: WinBridgeApp) {
        val clip = ClipboardBridge.readDirect(this)
        if (clip == null) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (!app.client.isConnected) {
            Toast.makeText(this, R.string.transfer_no_pc, Toast.LENGTH_SHORT).show()
            return
        }

        ClipboardBridge.remember(clip)
        app.scope.launch { app.client.sendMessage(clip) }
        Toast.makeText(this, R.string.clipboard_sent, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_APPLY = "com.cayatur.winbridge.CLIPBOARD_APPLY"
        const val ACTION_SEND = "com.cayatur.winbridge.CLIPBOARD_SEND"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HASH = "hash"
    }
}
