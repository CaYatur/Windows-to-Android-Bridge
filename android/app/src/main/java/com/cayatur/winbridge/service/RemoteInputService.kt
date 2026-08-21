package com.cayatur.winbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    // A drag is one gesture continued segment by segment, not a series of
    // gestures. The distinction matters twice over: a fling needs velocity,
    // which separate taps do not have, and the accessibility pipeline refuses a
    // new gesture while one is still running — so dispatching per move event
    // drops most of them, which is what "not stable" looks like from outside.
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastPoint: PointF? = null
    private var pendingPoint: PointF? = null
    private var finishing = false
    private var inFlight = false
    private var downAt = 0L
    private var moved = false

    private val handler = Handler(Looper.getMainLooper())

    fun touch(command: InputTouch) {
        val (x, y) = toPixels(command.x, command.y)

        when (command.action) {
            "down" -> {
                activeStroke = null
                pendingPoint = null
                finishing = false
                inFlight = false
                moved = false
                downAt = System.currentTimeMillis()
                lastPoint = PointF(x, y)
            }

            "move" -> {
                if (lastPoint == null) return
                moved = true
                // Coalesced: only the newest position matters, and the pump
                // sends one segment per completed gesture rather than queueing
                // every event the sender managed to produce.
                pendingPoint = PointF(x, y)
                pump()
            }

            "up" -> {
                val start = lastPoint ?: return

                if (!moved) {
                    // A press and release in the same place is a tap, and a tap
                    // is a short stroke rather than a zero-length one, which the
                    // platform rejects.
                    tap(start, System.currentTimeMillis() - downAt)
                    reset()
                    return
                }

                pendingPoint = PointF(x, y)
                finishing = true
                pump()
            }

            "cancel" -> reset()
        }
    }

    private fun tap(at: PointF, heldMs: Long) {
        val path = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x + 1f, at.y + 1f)
        }
        dispatch(path, heldMs.coerceIn(40, 900), willContinue = false)
    }

    private fun pump() {
        if (inFlight) return

        val from = lastPoint ?: return
        val to = pendingPoint ?: return
        pendingPoint = null

        // A zero-length path is refused outright, so a stationary finger just
        // holds the stroke open until the next real movement.
        if (kotlin.math.abs(to.x - from.x) < 1f && kotlin.math.abs(to.y - from.y) < 1f) {
            if (!finishing) return
        }

        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(if (to.x == from.x) to.x + 0.5f else to.x, if (to.y == from.y) to.y + 0.5f else to.y)
        }

        val willContinue = !finishing
        val previous = activeStroke
        val stroke = try {
            previous?.continueStroke(path, 0, SEGMENT_MS, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, willContinue)
        } catch (e: Exception) {
            Log.w(TAG, "stroke rejected: ${e.message}")
            reset()
            return
        }

        inFlight = true
        val ended = finishing

        val dispatched = runCatching {
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        inFlight = false
                        lastPoint = to
                        activeStroke = if (ended) null else stroke
                        if (ended) reset() else pump()
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        inFlight = false
                        activeStroke = null
                        if (ended) reset()
                    }
                },
                handler,
            )
        }.getOrDefault(false)

        if (!dispatched) {
            inFlight = false
            activeStroke = null
        }
    }

    private fun reset() {
        activeStroke = null
        lastPoint = null
        pendingPoint = null
        finishing = false
        inFlight = false
        moved = false
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
        /**
         * How long one drag segment takes. Short enough that the finger keeps up
         * with the cursor, long enough that the platform does not reject it —
         * below about 20 ms the gesture is treated as instantaneous and the app
         * being driven sees a jump rather than a drag.
         */
        private const val SEGMENT_MS = 32L

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
