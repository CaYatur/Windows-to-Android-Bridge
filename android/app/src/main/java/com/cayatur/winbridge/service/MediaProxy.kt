package com.cayatur.winbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.cayatur.winbridge.MainActivity
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.MediaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Publishes whatever the PC is playing as a real Android media session.
 *
 * The point is that it stops feeling like "a remote in an app": the track shows
 * up in the notification shade, on the lock screen and in the Quick Settings
 * media player, with working transport buttons, exactly as if it were playing
 * on the phone.
 *
 * Handing the system a position plus a playback speed is also what makes the
 * seek bar move smoothly. The system interpolates between our updates, so the
 * elapsed time advances every frame instead of stepping whenever a message
 * happens to arrive.
 */
class MediaProxy(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var session: MediaSessionCompat? = null
    private var lastArtHash: String? = null
    private var showing = false

    fun start() {
        if (session != null) return
        createChannel()

        session = MediaSessionCompat(context, "WinBridge").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onSkipToNext() = send("next")
                override fun onSkipToPrevious() = send("prev")
                override fun onStop() = clear()
                override fun onSeekTo(pos: Long) {
                    scope.launch { WinBridgeApp.instance.client.mediaCommand("seek", pos) }
                }
            })
            isActive = true
        }
    }

    private fun send(action: String) {
        scope.launch { WinBridgeApp.instance.client.mediaCommand(action) }
    }

    fun update(media: MediaState?, art: Bitmap?) {
        val active = session ?: return

        if (media?.title.isNullOrBlank()) {
            clear()
            return
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media?.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, media?.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, media?.album ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, media?.durMs ?: 0L)
            .apply { if (art != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) }
            .build()
        active.setMetadata(metadata)

        var actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        if (media?.canNext == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (media?.canPrev == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (media?.canSeek == true) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO

        val playing = media?.playing == true
        active.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    media?.posMs ?: 0L,
                    // Speed 1.0 while playing tells the system it may advance
                    // the position itself; 0 freezes it where we put it.
                    if (playing) 1.0f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )

        lastArtHash = media?.artHash
        showNotification(media, art, playing)
    }

    private fun showNotification(media: MediaState?, art: Bitmap?, playing: Boolean) {
        val active = session ?: return

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(media?.title)
            .setContentText(
                listOfNotNull(media?.artist, media?.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            )
            .setSubText(WinBridgeApp.instance.store.hostName)
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                MediaStyle()
                    .setMediaSession(active.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        builder.addAction(
            android.R.drawable.ic_media_previous,
            context.getString(R.string.media_previous),
            mediaAction(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
        )
        builder.addAction(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            context.getString(R.string.media_playpause),
            mediaAction(PlaybackStateCompat.ACTION_PLAY_PAUSE),
        )
        builder.addAction(
            android.R.drawable.ic_media_next,
            context.getString(R.string.media_next),
            mediaAction(PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
        )

        runCatching {
            manager().notify(NOTIFICATION_ID, builder.build())
            showing = true
        }
    }

    private fun mediaAction(action: Long): PendingIntent =
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, action)

    fun clear() {
        if (showing) {
            runCatching { manager().cancel(NOTIFICATION_ID) }
            showing = false
        }
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                .build(),
        )
    }

    fun stop() {
        clear()
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_media),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_media_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager().createNotificationChannel(channel)
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "winbridge.media"
        const val NOTIFICATION_ID = 2
    }
}

private typealias MediaButtonReceiver = androidx.media.session.MediaButtonReceiver
