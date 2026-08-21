package com.cayatur.winbridge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.StreamInfo
import com.cayatur.winbridge.protocol.TileCodec
import kotlin.math.abs

/**
 * Draws the PC screen and turns touches into mouse events.
 *
 * Tiles are decoded into one long-lived bitmap and blitted onto a second one
 * that holds the whole frame. Allocating per tile would be simpler and would
 * also hand the collector several megabytes a second at thirty frames, which is
 * felt as stutter and blamed on the network.
 *
 * The view is pan-and-zoomable because a 1080p desktop shown whole on a phone is
 * legible to nobody. Touches are mapped back through the inverse of the same
 * matrix, so a tap lands where it looks like it lands at any zoom.
 */
class PcScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Where mouse events go. Set by the activity. */
    var onMouse: ((action: String, x: Double, y: Double, button: String) -> Unit)? = null
    var onScroll: ((x: Double, y: Double, dy: Double) -> Unit)? = null

    /** True for tap-where-you-touch, false for trackpad-style relative movement. */
    var directTouch: Boolean = true

    private var frame: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var tile: Bitmap? = null
    private var tileWidth = 64
    private var tileHeight = 64
    private var columns = 0

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val point = FloatArray(2)

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fitted = false

    private var dragging = false
    private var pressed = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var scrolling = false
    private var lastScrollY = 0f

    /** Statistics the sender adapts from. */
    var framesPresented = 0
        private set
    var bytesReceived = 0L
        private set
    var lastTimestamp = 0
        private set

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previous = scale
                scale = (scale * detector.scaleFactor).coerceIn(0.4f, 6f)

                // Zoom around the pinch centre, not the corner: anchoring at 0,0
                // makes the thing you were looking at slide off screen.
                val focusX = detector.focusX
                val focusY = detector.focusY
                offsetX = focusX - (focusX - offsetX) * (scale / previous)
                offsetY = focusY - (focusY - offsetY) * (scale / previous)

                rebuildMatrix()
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!directTouch || dragging || scrolling) return
                val mapped = toSurface(e.x, e.y) ?: return
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onMouse?.invoke("click", mapped.first, mapped.second, "right")
                pressed = false
            }
        },
    )

    // ---- decoding -----------------------------------------------------------

    fun configure(info: StreamInfo) {
        if (!info.active || info.width <= 0 || info.height <= 0) return

        tileWidth = info.tileWidth.coerceAtLeast(1)
        tileHeight = info.tileHeight.coerceAtLeast(1)
        columns = info.columns.coerceAtLeast(1)

        val existing = frame
        if (existing != null && existing.width == info.width && existing.height == info.height) return

        existing?.recycle()
        val created = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        created.eraseColor(Color.BLACK)
        frame = created
        frameCanvas = Canvas(created)

        tile?.recycle()
        tile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)

        fitted = false
        invalidate()
    }

    fun onPacket(packet: MediaPacket) {
        val target = frameCanvas ?: return
        bytesReceived += packet.payloadLength

        if (packet.isKeyframe) target.drawColor(Color.BLACK)

        TileCodec.forEachTile(packet.payload, packet.payloadOffset, packet.payloadLength) { index, data, offset, length ->
            drawTile(target, index, data, offset, length)
        }

        if (packet.isEndOfFrame) {
            framesPresented++
            lastTimestamp = packet.timestampMs
            postInvalidateOnAnimation()
        }
    }

    private fun drawTile(target: Canvas, index: Int, data: ByteArray, offset: Int, length: Int) {
        if (columns == 0) return
        val x = (index % columns) * tileWidth
        val y = (index / columns) * tileHeight

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            // Reusing one buffer only works when the decode is exactly the same
            // size, which is every tile except the ones at the right and bottom
            // edges. Those fall back to a fresh allocation, and there are at most
            // one row and one column of them.
            inBitmap = tile
        }

        val decoded = try {
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } catch (_: IllegalArgumentException) {
            BitmapFactory.decodeByteArray(data, offset, length, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return

        target.drawBitmap(decoded, x.toFloat(), y.toFloat(), null)
        if (decoded !== tile) decoded.recycle()
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = frame ?: return

        if (!fitted && width > 0 && height > 0) {
            fitToWindow(source)
            fitted = true
        }

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, matrix, paint)
    }

    private fun fitToWindow(source: Bitmap) {
        scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        offsetX = (width - source.width * scale) / 2f
        offsetY = (height - source.height * scale) / 2f
        rebuildMatrix()
    }

    fun resetZoom() {
        frame?.let { fitToWindow(it); invalidate() }
    }

    private fun rebuildMatrix() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        matrix.invert(inverse)
    }

    // ---- touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.pointerCount >= 2) return handleTwoFinger(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                dragging = false
                scrolling = false
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()

                if (directTouch) {
                    toSurface(event.x, event.y)?.let { onMouse?.invoke("move", it.first, it.second, "left") }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressed || scaleDetector.isInProgress) return true

                val travel = maxOf(abs(event.x - downX), abs(event.y - downY))

                if (directTouch) {
                    // A finger that has moved past the slop is a drag, so the
                    // button goes down once and stays down until it lifts. Sending
                    // a click per move event would produce a stutter of clicks
                    // instead of a selection.
                    if (!dragging && travel > SLOP) {
                        dragging = true
                        toSurface(downX, downY)?.let { onMouse?.invoke("down", it.first, it.second, "left") }
                    }
                    if (dragging) {
                        toSurface(event.x, event.y)?.let { onMouse?.invoke("move", it.first, it.second, "left") }
                    }
                } else {
                    // Trackpad mode moves the pointer by the difference, scaled
                    // down so a small screen can still cross a large desktop.
                    val dx = (event.x - downX) / width * TRACKPAD_GAIN
                    val dy = (event.y - downY) / height * TRACKPAD_GAIN
                    if (abs(dx) > 0.0005 || abs(dy) > 0.0005) {
                        onMouse?.invoke("relative", dx.toDouble(), dy.toDouble(), "left")
                        downX = event.x
                        downY = event.y
                        dragging = travel > SLOP
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val quick = System.currentTimeMillis() - downAt < TAP_MS
                val still = maxOf(abs(event.x - downX), abs(event.y - downY)) < SLOP

                if (dragging && directTouch) {
                    toSurface(event.x, event.y)?.let { onMouse?.invoke("up", it.first, it.second, "left") }
                } else if (pressed && quick && (still || !directTouch)) {
                    if (directTouch) {
                        toSurface(event.x, event.y)?.let { onMouse?.invoke("click", it.first, it.second, "left") }
                    } else {
                        onMouse?.invoke("click", 0.0, 0.0, "left")
                    }
                }

                pressed = false
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging && directTouch) {
                    toSurface(event.x, event.y)?.let { onMouse?.invoke("up", it.first, it.second, "left") }
                }
                pressed = false
                dragging = false
            }
        }
        return true
    }

    private fun handleTwoFinger(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                scrolling = true
                pressed = false
                lastScrollY = (event.getY(0) + event.getY(1)) / 2f
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrolling || scaleDetector.isInProgress) return true
                val centre = (event.getY(0) + event.getY(1)) / 2f
                val delta = centre - lastScrollY
                if (abs(delta) < 6f) return true

                lastScrollY = centre
                val mapped = toSurface(event.getX(0), event.getY(0)) ?: return true
                onScroll?.invoke(mapped.first, mapped.second, (delta / height).toDouble())
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> scrolling = false
        }
        return true
    }

    /** View coordinates to normalised surface coordinates, or null when outside. */
    private fun toSurface(x: Float, y: Float): Pair<Double, Double>? {
        val source = frame ?: return null
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)

        val normalisedX = point[0] / source.width
        val normalisedY = point[1] / source.height
        if (normalisedX !in 0f..1f || normalisedY !in 0f..1f) return null
        return normalisedX.toDouble() to normalisedY.toDouble()
    }

    fun resetStats() {
        framesPresented = 0
        bytesReceived = 0
    }

    private companion object {
        const val SLOP = 14f
        const val TAP_MS = 350L

        /** A phone screen is a fraction of a desktop, so relative motion is amplified. */
        const val TRACKPAD_GAIN = 2.2f
    }
}
