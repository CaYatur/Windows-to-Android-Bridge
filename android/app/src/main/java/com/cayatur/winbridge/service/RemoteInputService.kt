package com.cayatur.winbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.InputGesture
import com.cayatur.winbridge.protocol.InputKey
import com.cayatur.winbridge.protocol.InputNav
import com.cayatur.winbridge.protocol.InputScroll
import com.cayatur.winbridge.protocol.InputText
import com.cayatur.winbridge.protocol.InputTouch
import java.util.concurrent.atomic.AtomicReference

/**
 * Injects what the PC sends into this phone.
 *
 * An accessibility service is the ceiling for this without root. An ordinary app
 * cannot deliver an event outside its own window; `dispatchGesture` can reach any
 * app, which is what makes viewing the phone from the PC actually useful rather
 * than a picture you can only look at.
 *
 * What that ceiling does *not* include, stated plainly because the difference
 * shows: it cannot touch windows marked FLAG_SECURE (banking apps, DRM video),
 * each gesture carries tens of milliseconds of dispatch overhead so this is not
 * scrcpy-grade, and text is injected into the focused field rather than
 * synthesised as key events.
 *
 * Two gates before anything is injected: the user has to have turned remote
 * input on, and a mirroring session has to be open. An accessibility service that
 * would tap the screen whenever a packet arrived is not something worth granting.
 */
class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
        Log.i(TAG, "remote input service connected")
    }

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing is observed on purpose. The service exists to dispatch, and
        // watching every window change on the device would cost battery for
        // information this feature never reads.
    }

    override fun onInterrupt() = Unit

    // ---- injection ---------------------------------------------------------

    private val dragPath = Path()
    private var dragging = false
    private var dragStart = 0L

    fun touch(command: InputTouch) {
        val (x, y) = toPixels(command.x, command.y)

        when (command.action) {
            "down" -> {
                dragPath.reset()
                dragPath.moveTo(x, y)
                dragging = true
                dragStart = System.currentTimeMillis()
            }

            "move" -> {
                if (!dragging) return
                dragPath.lineTo(x, y)

                // A drag is dispatched as one continued stroke rather than a
                // sequence of taps: a fling needs velocity, and separate taps
                // have none — the target app sees a stationary finger appearing
                // in a new place.
                val elapsed = (System.currentTimeMillis() - dragStart).coerceIn(20, 2000)
                dispatch(dragPath, elapsed, willContinue = true)
            }

            "up", "cancel" -> {
                if (!dragging) return
                dragPath.lineTo(x, y)
                val elapsed = (System.currentTimeMillis() - dragStart).coerceIn(30, 3000)
                dispatch(dragPath, elapsed, willContinue = false)
                dragging = false
            }
        }
    }

    fun gesture(command: InputGesture) {
        val path = Path()
        val points = command.points
        if (points.isEmpty()) return

        val (startX, startY) = toPixels(points.first().x, points.first().y)
        path.moveTo(startX, startY)
        for (point in points.drop(1)) {
            val (x, y) = toPixels(point.x, point.y)
            path.lineTo(x, y)
        }

        val duration = when (command.kind) {
            "long" -> maxOf(command.durationMs, 600)
            "tap" -> 40
            else -> command.durationMs.coerceIn(30, 5000)
        }

        if (command.kind == "double") {
            dispatch(path, 40, willContinue = false)
            dispatch(path, 40, willContinue = false)
            return
        }

        if (command.kind == "pinch" && command.points2.isNotEmpty()) {
            val second = Path()
            val (sx, sy) = toPixels(command.points2.first().x, command.points2.first().y)
            second.moveTo(sx, sy)
            for (point in command.points2.drop(1)) {
                val (x, y) = toPixels(point.x, point.y)
                second.lineTo(x, y)
            }
            dispatchMulti(listOf(path, second), duration.toLong())
            return
        }

        dispatch(path, duration.toLong(), willContinue = false)
    }

    fun scroll(command: InputScroll) {
        val (x, y) = toPixels(command.x, command.y)
        val metrics = resources.displayMetrics

        // dy is a fraction of the surface; converting it to pixels here keeps the
        // sender from needing to know this screen at all.
        val travel = (command.dy * metrics.heightPixels).toFloat()
        if (kotlin.math.abs(travel) < 4f) return

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, (y + travel).coerceIn(0f, metrics.heightPixels - 1f))
        }
        dispatch(path, 120, willContinue = false)
    }

    fun navigate(command: InputNav) {
        val action = when (command.action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "split" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "dismiss" -> GLOBAL_ACTION_BACK
            "screenshot" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) GLOBAL_ACTION_TAKE_SCREENSHOT else null
            "lock" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else null
            else -> null
        } ?: return

        performGlobalAction(action)
    }

    fun key(command: InputKey) {
        when (command.code.lowercase()) {
            "back", "escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "enter" -> submitFocused()
            else -> Log.d(TAG, "no mapping for key \"${command.code}\"")
        }
    }

    /**
     * Types into whatever has focus.
     *
     * ACTION_SET_TEXT replaces the field, so the existing content is read and the
     * new text appended — otherwise typing a second word would delete the first.
     * If nothing focusable is found, the text goes to the clipboard and the user
     * is told, which is more use than failing silently.
     */
    fun text(command: InputText) {
        val node = focusedEditable()
        if (node == null) {
            Log.i(TAG, "no focused text field; leaving the text on the clipboard")
            com.cayatur.winbridge.feature.ClipboardBridge.applyDirect(
                this, com.cayatur.winbridge.feature.ClipboardBridge.build(command.text),
            )
            return
        }

        val existing = node.text?.toString().orEmpty()
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + command.text,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        node.recycleCompat()
    }

    private fun submitFocused() {
        val node = focusedEditable() ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycleCompat()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    private fun dispatch(path: Path, durationMs: Long, willContinue: Boolean) {
        runCatching {
            val stroke = GestureDescription.StrokeDescription(
                Path(path), 0, durationMs.coerceIn(10, 10_000), willContinue,
            )
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.onFailure { Log.w(TAG, "gesture refused: ${it.message}") }
    }

    private fun dispatchMulti(paths: List<Path>, durationMs: Long) {
        runCatching {
            val builder = GestureDescription.Builder()
            for (path in paths) {
                builder.addStroke(
                    GestureDescription.StrokeDescription(Path(path), 0, durationMs.coerceIn(10, 10_000)),
                )
            }
            dispatchGesture(builder.build(), null, null)
        }.onFailure { Log.w(TAG, "multi-touch gesture refused: ${it.message}") }
    }

    private fun toPixels(x: Double, y: Double): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        return (x.coerceIn(0.0, 1.0) * (metrics.widthPixels - 1)).toFloat() to
            (y.coerceIn(0.0, 1.0) * (metrics.heightPixels - 1)).toFloat()
    }

    companion object {
        private val instance = AtomicReference<RemoteInputService?>(null)

        /** Null when the user has not granted accessibility access. */
        fun current(): RemoteInputService? = instance.get()

        fun isEnabled(): Boolean = instance.get() != null

        /**
         * True when injection would actually happen. Used to tell the PC whether
         * to offer touch control at all, rather than letting it discover that
         * nothing happens when the user taps.
         */
        fun canInject(context: Context): Boolean =
            isEnabled() && WinBridgeApp.instance.store.allowRemoteInput

        /** Whether a mirroring session is open. Injection is refused otherwise. */
        @Volatile
        var sessionOpen: Boolean = false
    }
}
