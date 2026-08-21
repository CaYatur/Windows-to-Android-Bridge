package com.cayatur.winbridge.feature

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.cayatur.winbridge.R
import com.cayatur.winbridge.net.TAG
import com.cayatur.winbridge.service.NotificationActionReceiver

/**
 * Makes the phone findable from the PC.
 *
 * Plays on the alarm stream and raises its volume for the duration, because the
 * one time someone uses this is when the phone is silenced and lost — a feature
 * that respects the ringer setting here would be a feature that never works when
 * it is needed. The previous volume is put back afterwards.
 */
class Ringer(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var previousVolume: Int? = null
    private var stopAt: Long = 0

    @Synchronized
    fun start(seconds: Int) {
        stop()
        stopAt = System.currentTimeMillis() + seconds.coerceIn(3, 300) * 1000L

        val audio = context.getSystemService(AudioManager::class.java)
        runCatching {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }

        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "could not ring: ${it.message}") }

        vibrate()
        notifyOngoing()

        // Bounded on purpose. A ring that keeps going because a message was lost
        // is worse than one that stops slightly early.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() >= stopAt) stop()
        }, seconds.coerceIn(3, 300) * 1000L)
    }

    @Synchronized
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null

        previousVolume?.let { level ->
            runCatching {
                context.getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            }
        }
        previousVolume = null

        runCatching { vibrator()?.cancel() }
        Notices.clear(context, Notices.ID_RING)
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 500, 400)
        runCatching {
            vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    private fun notifyOngoing() {
        val stop = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, Notices.CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.ring_title))
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setFullScreenIntent(stop, true)
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.ring_stop), stop).build(),
            )
            .build()

        Notices.manager(context).notify(Notices.ID_RING, notification)
    }
}
