package com.cayatur.winbridge.feature

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.protocol.MediaPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays the PCM the PC sends — its output, or its microphone.
 *
 * Written in low-latency mode with a small buffer, because the point of carrying
 * raw PCM at all is that the sound lines up with the picture. A generous buffer
 * would hide network jitter and cost exactly the delay the whole design is
 * trying not to have, so instead the queue is short and a late packet is
 * dropped: a click is better than drifting half a second behind.
 */
object AudioPlayback {

    private val tracks = ConcurrentHashMap<Byte, Sink>()

    fun start(stream: Byte, rate: Int, channels: Int, voiceCall: Boolean = false): Boolean {
        stop(stream)
        return runCatching {
            tracks[stream] = Sink(rate, channels, voiceCall)
            true
        }.getOrElse {
            Log.w(TAG, "audio playback would not start: ${it.message}")
            false
        }
    }

    fun feed(packet: MediaPacket) {
        val sink = tracks[packet.stream] ?: return
        sink.write(packet.payload, packet.payloadOffset, packet.payloadLength)
    }

    fun stop(stream: Byte) {
        tracks.remove(stream)?.release()
    }

    fun stopAll() {
        tracks.keys.toList().forEach { stop(it) }
    }

    fun isPlaying(stream: Byte): Boolean = tracks.containsKey(stream)

    private class Sink(rate: Int, channels: Int, voiceCall: Boolean) {
        private val track: AudioTrack

        init {
            val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)

            // Two minimum buffers: one being played while the next is written.
            // Larger is smoother and audibly later.
            val size = maxOf(minimum * 2, rate * channels * 2 / 10)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA,
                        )
                        .setContentType(
                            if (voiceCall) AudioAttributes.CONTENT_TYPE_SPEECH
                            else AudioAttributes.CONTENT_TYPE_MUSIC,
                        )
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            track.play()
        }

        fun write(data: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            runCatching {
                // Non-blocking: if the track cannot take it, the link is ahead of
                // the speaker and blocking here would back pressure into the
                // receive loop, stalling everything else on the socket.
                track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            }
        }

        fun release() {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }
}
