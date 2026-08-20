package com.cayatur.winbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.feature.Notices
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.AudioInfo
import com.cayatur.winbridge.protocol.MediaFlags
import com.cayatur.winbridge.protocol.MediaKind
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.StreamIds
import com.cayatur.winbridge.protocol.StreamInfo
import com.cayatur.winbridge.protocol.TileCodec
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Shares this phone screen and audio with the PC.
 *
 * Separate from [BridgeService] because the foreground-service type has to match
 * what is actually being captured: Android 14 refuses a service that declares
 * `mediaProjection` without an active projection, and it will not let one that
 * declares `connectedDevice` do the capturing.
 *
 * The consent dialog is unavoidable and comes back every session. Nothing here
 * can pre-approve it, which is why the PC-side viewer opens with an explanation
 * rather than waiting silently for a first frame.
 */
class CaptureService : Service() {

    private val app get() = application as WinBridgeApp

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var audio: AudioCapture? = null
    private var microphone: AudioCapture? = null

    private var encoder: TileEncoder? = null
    private var seq = 0
    private var lastFrameAt = 0L
    private var started = 0L

    private var maxFps = 30
    private var quality = 60
    private var maxEdge = 1080

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notices.ensureChannels(this)
        started = System.currentTimeMillis()
        startForeground(Notices.ID_CAPTURE, buildNotification(), foregroundTypes())
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> { stopEverything(); stopSelf() }
            ACTION_START_MIC -> startMicrophone(
                intent.getIntExtra(EXTRA_RATE, 48000),
                intent.getIntExtra(EXTRA_CHANNELS, 1),
            )
            ACTION_STOP_MIC -> { microphone?.stop(); microphone = null }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null) {
            Log.w(TAG, "capture asked for without a projection grant")
            stopSelf()
            return
        }

        maxFps = intent.getIntExtra(EXTRA_MAX_FPS, 30).coerceIn(1, 60)
        quality = intent.getIntExtra(EXTRA_QUALITY, 60).coerceIn(10, 95)
        maxEdge = intent.getIntExtra(EXTRA_MAX_EDGE, 1080).coerceAtLeast(240)

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)?.also { active ->
            // Registering a callback is mandatory from Android 14; without one
            // the projection is torn down as soon as it starts.
            active.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection revoked")
                    stopEverything()
                    stopSelf()
                }
            }, handlerFor("winbridge-projection"))
        }

        if (projection == null) {
            Log.w(TAG, "the projection grant was refused")
            stopSelf()
            return
        }

        startScreen()
        if (app.store.screenShareAudio) startPlaybackCapture()
    }

    private fun handlerFor(name: String): Handler {
        if (handler == null) {
            thread = HandlerThread(name).apply { start() }
            handler = Handler(thread!!.looper)
        }
        return handler!!
    }

    // ---- screen -------------------------------------------------------------

    private fun startScreen() {
        val metrics = resources.displayMetrics
        val scale = min(1.0, maxEdge.toDouble() / max(metrics.widthPixels, metrics.heightPixels))

        // Rounded to a multiple of two, and captured at the target size directly:
        // asking the VirtualDisplay for the size we want lets the compositor do
        // the scaling, which is free, instead of resampling every frame in Java.
        val width = (metrics.widthPixels * scale).toInt() / 2 * 2
        val height = (metrics.heightPixels * scale).toInt() / 2 * 2

        val encode = TileEncoder(width, height, quality)
        encoder = encode

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = imageReader

        val callback = handlerFor("winbridge-capture")
        imageReader.setOnImageAvailableListener({ onFrame(it) }, callback)

        display = projection?.createVirtualDisplay(
            "WinBridge",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, callback,
        )

        RemoteInputService.sessionOpen = true

        app.scope.launch {
            app.client.sendMessage(
                StreamInfo(
                    stream = StreamIds.name(StreamIds.PHONE_SCREEN),
                    active = true,
                    width = width,
                    height = height,
                    tileWidth = TileEncoder.TILE,
                    tileHeight = TileEncoder.TILE,
                    columns = encode.columns,
                    rows = encode.rows,
                    interact = com.cayatur.winbridge.service.RemoteInputService.canInject(this@CaptureService),
                ),
            )
        }

        Log.i(TAG, "screen capture at ${width}x$height, up to $maxFps fps")
    }

    private fun onFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = System.currentTimeMillis()
            val budget = 1000L / maxFps

            // Frames arrive whenever the screen changes, which can be far faster
            // than the link can carry. Dropping here, before any encoding, is
            // much cheaper than encoding and then dropping.
            if (now - lastFrameAt < budget) return
            lastFrameAt = now

            val encode = encoder ?: return
            val plane = image.planes[0]
            encode.frame(plane.buffer, plane.rowStride, plane.pixelStride) { packetBytes, keyframe, last ->
                app.client.trySendMedia(
                    MediaPacket(
                        kind = MediaKind.VIDEO,
                        stream = StreamIds.PHONE_SCREEN,
                        seq = seq++,
                        timestampMs = (now - started).toInt(),
                        flags = (if (keyframe) MediaFlags.KEYFRAME else 0) or
                            (if (last) MediaFlags.END_OF_FRAME else 0),
                        payload = packetBytes,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed: ${e.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    // ---- audio --------------------------------------------------------------

    private fun startPlaybackCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "playback capture needs Android 10")
            return
        }
        val active = projection ?: return

        val configuration = AudioPlaybackCaptureConfiguration.Builder(active)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audio = AudioCapture(
            stream = StreamIds.PHONE_AUDIO,
            rate = 48000,
            channels = 2,
            build = { format, buffer ->
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buffer)
                    .setAudioPlaybackCaptureConfig(configuration)
                    .build()
            },
        ).also { it.start(app) }

        app.scope.launch {
            app.client.sendMessage(
                AudioInfo(
                    stream = StreamIds.name(StreamIds.PHONE_AUDIO),
                    active = true, rate = 48000, channels = 2, frameMs = 20,
                ),
            )
        }
    }

    private fun startMicrophone(rate: Int, channels: Int) {
        microphone?.stop()
        microphone = AudioCapture(
            stream = StreamIds.PHONE_MIC,
            rate = rate,
            channels = channels,
            build = { format, buffer ->
                @Suppress("MissingPermission")
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buffer)
                    .build()
            },
        ).also { it.start(app) }
    }

    // ---- lifecycle ----------------------------------------------------------

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_STOP_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, Notices.CHANNEL_CAPTURE)
            .setContentTitle(
                getString(R.string.capture_running, app.state.host.value?.name ?: "PC"),
            )
            .setSmallIcon(R.drawable.ic_monitor)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.capture_stop), stop).build(),
            )
            .build()
    }

    private fun stopEverything() {
        RemoteInputService.sessionOpen = false

        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        audio?.stop()
        microphone?.stop()
        thread?.quitSafely()

        display = null
        reader = null
        projection = null
        audio = null
        microphone = null
        encoder = null
        handler = null
        thread = null

        app.scope.launch {
            runCatching {
                app.client.sendMessage(
                    StreamInfo(
                        stream = StreamIds.name(StreamIds.PHONE_SCREEN),
                        active = false,
                        reason = "stopped on the phone",
                    ),
                )
            }
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.cayatur.winbridge.CAPTURE_START"
        const val ACTION_STOP = "com.cayatur.winbridge.CAPTURE_STOP"
        const val ACTION_START_MIC = "com.cayatur.winbridge.MIC_START"
        const val ACTION_STOP_MIC = "com.cayatur.winbridge.MIC_STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MAX_FPS = "maxFps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_EDGE = "maxEdge"
        const val EXTRA_RATE = "rate"
        const val EXTRA_CHANNELS = "channels"

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP),
            )
        }

        fun startMicrophone(context: Context, rate: Int, channels: Int) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START_MIC
                putExtra(EXTRA_RATE, rate)
                putExtra(EXTRA_CHANNELS, channels)
            }
            context.startForegroundService(intent)
        }
    }
}

/**
 * Turns an RGBA frame into JPEG tiles, sending only the ones whose contents
 * changed.
 *
 * Reading the pixels straight out of the ImageReader buffer avoids building a
 * Bitmap of the whole frame every time; at 30 fps that allocation alone is
 * several megabytes a second for something thrown away immediately.
 */
private class TileEncoder(val width: Int, val height: Int, val quality: Int) {

    val columns = (width + TILE - 1) / TILE
    val rows = (height + TILE - 1) / TILE

    private val hashes = LongArray(columns * rows)
    private var first = true

    private val tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(TILE * TILE)
    private val jpeg = ByteArrayOutputStream(24 * 1024)
    private val packet = ByteArrayOutputStream(TileCodec.MAX_PACKET_BYTES)

    /** Calls [emit] once per outgoing packet: bytes, keyframe, last-of-frame. */
    fun frame(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        emit: (ByteArray, Boolean, Boolean) -> Unit,
    ) {
        val keyframe = first
        var keyframeSent = false
        first = false
        var changed = 0

        packet.reset()

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = column * TILE
                val y = row * TILE
                val tileWidth = min(TILE, width - x)
                val tileHeight = min(TILE, height - y)
                val index = row * columns + column

                val hash = hash(buffer, rowStride, pixelStride, x, y, tileWidth, tileHeight)
                if (!keyframe && hashes[index] == hash) continue
                hashes[index] = hash

                val bytes = compress(buffer, rowStride, pixelStride, x, y, tileWidth, tileHeight)

                if (packet.size() > 0 && packet.size() + bytes.size + 6 > TileCodec.MAX_PACKET_BYTES) {
                    emit(packet.toByteArray(), keyframe && !keyframeSent, false)
                    keyframeSent = true
                    packet.reset()
                }

                TileCodec.writeTile(packet, index, bytes)
                changed++
            }
        }

        if (changed > 0 && packet.size() > 0) {
            emit(packet.toByteArray(), keyframe && !keyframeSent, true)
        }
    }

    /**
     * FNV-1a over every fourth pixel. Hashing every byte of a 1080-wide frame is
     * megabytes of reads per frame for a comparison that gets thrown away;
     * sampling still catches a text caret, which is the smallest thing that
     * actually moves.
     */
    private fun hash(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        x: Int, y: Int, tileWidth: Int, tileHeight: Int,
    ): Long {
        var value = -3750763034362895579L   // FNV offset basis
        for (row in 0 until tileHeight) {
            var at = (y + row) * rowStride + x * pixelStride
            var column = 0
            while (column < tileWidth) {
                value = (value xor (buffer.get(at).toLong() and 0xFF)) * 1099511628211L
                value = (value xor (buffer.get(at + 1).toLong() and 0xFF)) * 1099511628211L
                value = (value xor (buffer.get(at + 2).toLong() and 0xFF)) * 1099511628211L
                at += pixelStride * 4
                column += 4
            }
        }
        return value
    }

    private fun compress(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        x: Int, y: Int, tileWidth: Int, tileHeight: Int,
    ): ByteArray {
        for (row in 0 until tileHeight) {
            var at = (y + row) * rowStride + x * pixelStride
            for (column in 0 until tileWidth) {
                val r = buffer.get(at).toInt() and 0xFF
                val g = buffer.get(at + 1).toInt() and 0xFF
                val b = buffer.get(at + 2).toInt() and 0xFF
                // The source is RGBA and Bitmap wants ARGB; alpha is forced
                // opaque because a screen grab has none worth carrying.
                pixels[row * tileWidth + column] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                at += pixelStride
            }
        }

        val target = if (tileWidth == TILE && tileHeight == TILE) {
            tile.also { it.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE) }
        } else {
            // Edge tiles are narrower or shorter; encoding them at their real
            // size avoids sending a black border the receiver has to know about.
            Bitmap.createBitmap(pixels, 0, tileWidth, tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
        }

        jpeg.reset()
        target.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
        if (target !== tile) target.recycle()
        return jpeg.toByteArray()
    }

    companion object {
        const val TILE = 64
    }
}

/**
 * One PCM capture stream. Sixteen-bit little-endian, which is what the wire
 * format is, so nothing has to be converted on the way out.
 */
private class AudioCapture(
    private val stream: Byte,
    private val rate: Int,
    private val channels: Int,
    private val build: (AudioFormat, Int) -> AudioRecord,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var seq = 0

    fun start(app: WinBridgeApp) {
        val mask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(mask)
            .build()

        val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = max(minimum, rate * channels * 2 / 5)

        val created = runCatching { build(format, bufferSize) }.getOrElse {
            Log.w(TAG, "audio capture unavailable: ${it.message}")
            return
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "audio capture would not initialise")
            runCatching { created.release() }
            return
        }

        record = created
        running = true
        created.startRecording()

        // 20 ms per packet: short enough that a dropped one is inaudible, long
        // enough that the per-packet overhead stays negligible.
        val frameBytes = rate * channels * 2 * 20 / 1000

        thread = Thread({
            val buffer = ByteArray(frameBytes)
            while (running) {
                val read = created.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                app.client.trySendMedia(
                    MediaPacket(
                        kind = MediaKind.AUDIO,
                        stream = stream,
                        seq = seq++,
                        timestampMs = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                        flags = MediaFlags.END_OF_FRAME,
                        payload = buffer,
                        payloadOffset = 0,
                        payloadLength = read,
                    ),
                )
            }
        }, "winbridge-audio-$stream").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
    }
}
