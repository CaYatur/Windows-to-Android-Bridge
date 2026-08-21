package com.cayatur.winbridge.feature

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.ClipboardMessage

/**
 * Borrows input focus for a few milliseconds so the clipboard can be read from
 * the background.
 *
 * Android 10 and later gate `getPrimaryClip` on one thing: does the calling UID
 * own the currently focused window. Not the foreground app, not a running
 * foreground service, not an accessibility service — the *focused window*. That
 * is why every attempt to read the clipboard from the bridge service comes back
 * null with "Denying clipboard access" in the system log.
 *
 * An Activity satisfies the rule, but starting one from the background is itself
 * restricted, and on the versions where it is allowed it still pauses whatever
 * the user was copying from. A one-pixel overlay window does not: it is added by
 * the WindowManager directly, so no activity start is involved, and it takes
 * focus for exactly as long as it takes to read a string.
 *
 * The flags matter more than the size:
 *
 *   * not FLAG_NOT_FOCUSABLE — the whole point is to become the focused window;
 *   * FLAG_NOT_TOUCHABLE — touches carry on to the app underneath, so a copy
 *     made mid-scroll does not swallow the next tap;
 *   * FLAG_ALT_FOCUSABLE_IM — the keyboard is told to ignore us, so the input
 *     method stays exactly where it was instead of closing under the user.
 *
 * It needs "display over other apps", which is a permission the user grants
 * once. Without it this tier is simply skipped and the gesture routes — the
 * tile, the share sheet, the shortcut — remain.
 */
object ClipboardFocus {

    private val handler = Handler(Looper.getMainLooper())

    /** True when the overlay tier is available at all. */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Adds the window, reads once focus has actually landed, and takes it away
     * again. [onResult] is called on the main thread, with null when the read
     * was still refused.
     */
    fun read(context: Context, onResult: (ClipboardMessage?) -> Unit) {
        if (!granted(context)) {
            onResult(null)
            return
        }
        handler.post { attach(context, onResult) }
    }

    private fun attach(context: Context, onResult: (ClipboardMessage?) -> Unit) {
        val windows = context.getSystemService(WindowManager::class.java)
        if (windows == null) {
            onResult(null)
            return
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val view = View(context)
        var settled = false

        // Two ways out, because neither is reliable alone: the focus callback
        // fires immediately on most builds, and on the ones where it does not
        // the timeout still gets an answer rather than leaving a window pinned
        // over the user's screen.
        val finish = { result: ClipboardMessage? ->
            if (!settled) {
                settled = true
                runCatching { windows.removeViewImmediate(view) }
                onResult(result)
            }
        }

        view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !settled) finish(ClipboardBridge.readDirect(context))
        }

        val added = runCatching { windows.addView(view, params) }
            .onFailure { Log.i(TAG, "clipboard focus window refused: ${it.message}") }
            .isSuccess

        if (!added) {
            settled = true
            onResult(null)
            return
        }

        handler.postDelayed({ finish(ClipboardBridge.readDirect(context)) }, TIMEOUT_MS)
    }

    /**
     * Long enough for a window to be added and focused on a slow device, short
     * enough that nothing is visibly interrupted if it never is.
     */
    private const val TIMEOUT_MS = 350L
}
