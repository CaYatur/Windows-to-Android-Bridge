package com.cayatur.winbridge.feature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cayatur.winbridge.R
import com.cayatur.winbridge.service.NotificationActionReceiver

/**
 * Notification channels and the handful of notifications this app posts itself.
 *
 * Separate channels rather than one, because the four things it has to say have
 * genuinely different urgency: a clipboard is worth a quiet line, a file landing
 * is worth a peek, and the PC ringing the phone is meant to be found from across
 * a room. One channel would force a single choice on all of them, and the user
 * would turn the lot off to stop the noisy one.
 */
object Notices {

    const val CHANNEL_TRANSFERS = "winbridge.transfers"
    const val CHANNEL_CLIPBOARD = "winbridge.clipboard"
    const val CHANNEL_CAPTURE = "winbridge.capture"
    const val CHANNEL_ALERTS = "winbridge.alerts"

    const val ID_CLIPBOARD = 1001
    const val ID_TRANSFER = 1002
    const val ID_CAPTURE = 1003
    const val ID_RING = 1004
    const val ID_AUTOMATION = 1005

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSFERS,
                context.getString(R.string.channel_transfers),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_transfers_desc) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CLIPBOARD,
                context.getString(R.string.channel_clipboard),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_clipboard_desc)
                setShowBadge(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_capture_desc) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_alerts_desc) },
        )
    }

    fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * The last-resort clipboard tier: a notification whose tap applies the text.
     * A tap is a user gesture, which Android always allows, so this works even
     * where nothing else does.
     */
    fun clipboardArrived(context: Context, text: String, from: String?) {
        val apply = PendingIntent.getBroadcast(
            context,
            ID_CLIPBOARD,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_APPLY_CLIPBOARD
                putExtra(NotificationActionReceiver.EXTRA_TEXT, text)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, CHANNEL_CLIPBOARD)
            .setContentTitle(context.getString(R.string.clipboard_from_pc, from ?: "PC"))
            .setContentText(if (text.length > 120) text.take(120) + "…" else text)
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentIntent(apply)
            .setAutoCancel(true)
            .build()

        manager(context).notify(ID_CLIPBOARD, notification)
    }

    fun simple(context: Context, channel: String, id: Int, title: String, text: String?) {
        val notification = Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notification)
    }

    fun progress(context: Context, id: Int, title: String, done: Long, total: Long) {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val notification = Notification.Builder(context, CHANNEL_TRANSFERS)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager(context).notify(id, notification)
    }

    fun clear(context: Context, id: Int) = manager(context).cancel(id)
}
