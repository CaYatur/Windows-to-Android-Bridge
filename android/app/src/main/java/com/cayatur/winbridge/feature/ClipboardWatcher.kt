package com.cayatur.winbridge.feature

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.TAG

/**
 * Pushes the phone clipboard to the PC as soon as it changes, without anyone
 * having to press anything.
 *
 * The change *event* is not restricted — Android delivers it to any registered
 * listener. What is restricted is the read that follows, which is allowed only
 * for the app with input focus, the default input method, or, on many builds, a
 * process that also runs an accessibility service. So this registers once and
 * simply tries: when the read succeeds the clipboard syncs silently, and when it
 * does not, the tile, the shortcut and the share sheet are still there.
 *
 * Which of those happened is logged rather than assumed, because the rule
 * differs between versions and OEM builds, and a feature documented against the
 * wrong rule is a feature that quietly does nothing on half the devices.
 */
object ClipboardWatcher {

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** How many changes were seen, and how many could actually be read. */
    @Volatile var seen: Int = 0
        private set

    @Volatile var read: Int = 0
        private set

    fun attach(context: Context) {
        if (listener != null) return

        val manager = runCatching { ClipboardBridge.manager(context) }.getOrNull() ?: return
        val app = context.applicationContext as? WinBridgeApp ?: return

        val handler = ClipboardManager.OnPrimaryClipChangedListener {
            seen++
            if (!app.store.clipboardToPc) return@OnPrimaryClipChangedListener
            if (!app.client.isConnected) return@OnPrimaryClipChangedListener

            val clip = ClipboardBridge.readDirect(context)
            if (clip == null) {
                Log.i(TAG, "clipboard changed but could not be read in the background")
                return@OnPrimaryClipChangedListener
            }

            // Applying a clipboard from the PC raises this same event, so without
            // the echo check the two machines hand the string back and forth.
            if (ClipboardBridge.isEcho(clip)) return@OnPrimaryClipChangedListener

            read++
            ClipboardBridge.remember(clip)
            app.launch { runCatching { app.client.sendMessage(clip) } }
        }

        runCatching { manager.addPrimaryClipChangedListener(handler) }
            .onSuccess {
                listener = handler
                Log.i(TAG, "clipboard watcher attached")
            }
            .onFailure { Log.w(TAG, "clipboard watcher unavailable: ${it.message}") }
    }

    fun detach(context: Context) {
        val handler = listener ?: return
        runCatching { ClipboardBridge.manager(context).removePrimaryClipChangedListener(handler) }
        listener = null
    }

    /** For the diagnostics row: whether background reads are actually working here. */
    fun describe(): String = when {
        seen == 0 -> "no clipboard change seen yet"
        read == 0 -> "changes seen, but Android blocks reading them in the background"
        else -> "$read of $seen changes sent automatically"
    }
}
