package com.cayatur.winbridge.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.ClipboardBridge
import kotlinx.coroutines.launch

/**
 * Exists for a few frames so that the clipboard can be touched.
 *
 * Android hands clipboard *reads* to whichever UID owns the focused window. A
 * foreground service owns none; an Activity does — but not yet in `onCreate`.
 * Window focus arrives after `onResume`, at [onWindowFocusChanged], and reading
 * any earlier than that is refused exactly as if the activity had never been
 * started. That single line of timing is the difference between this feature
 * working and it silently returning nothing, which is what it used to do.
 *
 * Writes are not focus-gated, so applying a clipboard from the PC happens
 * immediately and the window closes without ever being drawn.
 *
 * It is also the target of the "Clipboard to PC" shortcut and the Quick Settings
 * tile, both of which are user gestures and therefore always allowed.
 */
class ClipboardRelayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_APPLY) {
            apply()
            close()
            return
        }

        // Focus normally lands within a frame or two. The timeout is not the
        // expected path; it is there so that a build which never grants focus to
        // a fully transparent window closes this rather than leaving it open.
        handler.postDelayed({ send() }, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) send()
    }

    private fun close() {
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

    private fun send() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)

        val app = WinBridgeApp.instance
        val clip = ClipboardBridge.readDirect(this)

        when {
            clip == null -> Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()

            !app.client.isConnected ->
                Toast.makeText(this, R.string.transfer_no_pc, Toast.LENGTH_SHORT).show()

            else -> {
                ClipboardBridge.remember(clip)
                app.scope.launch { app.client.sendMessage(clip) }
                Toast.makeText(this, R.string.clipboard_sent, Toast.LENGTH_SHORT).show()
            }
        }

        close()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_APPLY = "com.cayatur.winbridge.CLIPBOARD_APPLY"
        const val ACTION_SEND = "com.cayatur.winbridge.CLIPBOARD_SEND"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HASH = "hash"

        private const val FOCUS_TIMEOUT_MS = 1200L
    }
}
