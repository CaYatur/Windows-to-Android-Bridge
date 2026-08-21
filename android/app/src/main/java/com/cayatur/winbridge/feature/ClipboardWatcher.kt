package com.cayatur.winbridge.feature

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.TAG

/**
 * Pushes the phone clipboard to the PC as soon as it changes, without anyone
 * having to press anything.
 *
 * The change *event* is not restricted — Android delivers it to any registered
 * listener. What is restricted is the read that follows, which since Android 10
 * is allowed only for the UID that owns the focused window (or the default input
 * method). A foreground service owns no window, so the obvious implementation
 * gets the event, reads null, and looks broken.
 *
 * So the read is a ladder rather than a call:
 *
 *   1. [ClipboardBridge.readDirect] — free, and it works whenever WinBridge is
 *      the app in front, which covers copying from the mirror or from the app;
 *   2. [ClipboardFocus] — a one-pixel window that takes focus for a few
 *      milliseconds, where the user has granted "display over other apps";
 *   3. nothing, and the tile, shortcut and share sheet stay as the honest
 *      answer — each of those is a user gesture, which is always permitted.
 *
 * Which rung answered is counted rather than assumed, because the rule differs
 * between versions and OEM builds and the settings screen should be able to say
 * what is actually happening on *this* phone.
 */
object ClipboardWatcher {

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** How many changes were seen, how many were read, how many were refused. */
    @Volatile var seen: Int = 0
        private set

    @Volatile var read: Int = 0
        private set

    @Volatile var blocked: Int = 0
        private set

    /** Which rung last produced text, for the diagnostics line. */
    @Volatile var route: String = ""
        private set

    /**
     * One copy can raise the event more than once — some apps set the clip, then
     * set it again with a richer MIME type. Without this the same string is sent
     * twice and, worse, the second attempt borrows focus a second time.
     */
    private var lastEventAt = 0L

    fun attach(context: Context) {
        if (listener != null) return

        val manager = runCatching { ClipboardBridge.manager(context) }.getOrNull() ?: return
        val app = context.applicationContext as? WinBridgeApp ?: return

        val handler = ClipboardManager.OnPrimaryClipChangedListener {
            seen++

            val now = System.currentTimeMillis()
            if (now - lastEventAt < DEBOUNCE_MS) return@OnPrimaryClipChangedListener
            lastEventAt = now

            if (!app.store.clipboardToPc) return@OnPrimaryClipChangedListener
            if (!app.client.isConnected) return@OnPrimaryClipChangedListener

            val direct = ClipboardBridge.readDirect(context)
            if (direct != null) {
                deliver(app, direct, "direct")
                return@OnPrimaryClipChangedListener
            }

            if (!ClipboardFocus.granted(context)) {
                blocked++
                Log.i(TAG, "clipboard changed; background read refused and no overlay permission")
                return@OnPrimaryClipChangedListener
            }

            ClipboardFocus.read(context) { borrowed ->
                if (borrowed == null) {
                    blocked++
                    Log.i(TAG, "clipboard changed; refused even with a focused window")
                } else {
                    deliver(app, borrowed, "overlay")
                }
            }
        }

        runCatching { manager.addPrimaryClipChangedListener(handler) }
            .onSuccess {
                listener = handler
                Log.i(TAG, "clipboard watcher attached")
            }
            .onFailure { Log.w(TAG, "clipboard watcher unavailable: ${it.message}") }
    }

    private fun deliver(app: WinBridgeApp, clip: com.cayatur.winbridge.protocol.ClipboardMessage, via: String) {
        // Applying a clipboard from the PC raises this same event, so without
        // the echo check the two machines hand the string back and forth.
        if (ClipboardBridge.isEcho(clip)) return

        read++
        route = via
        ClipboardBridge.remember(clip)
        Log.i(TAG, "clipboard sent to PC via $via (${clip.text?.length ?: 0} chars)")
        app.launch { runCatching { app.client.sendMessage(clip) } }
    }

    fun detach(context: Context) {
        val handler = listener ?: return
        runCatching { ClipboardBridge.manager(context).removePrimaryClipChangedListener(handler) }
        listener = null
    }

    /** For the diagnostics row: whether background reads are actually working here. */
    fun describe(context: Context): String = when {
        seen == 0 -> context.getString(R.string.clip_diag_idle)
        read > 0 -> context.getString(R.string.clip_diag_working, read, seen)
        ClipboardFocus.granted(context) -> context.getString(R.string.clip_diag_refused)
        else -> context.getString(R.string.clip_diag_needs_overlay)
    }

    private const val DEBOUNCE_MS = 250L
}
