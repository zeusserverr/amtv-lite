package com.ralph.applemusictv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder

/**
 * Runs only while music plays. It tells Android "this app is playing music" so that, after Home is pressed,
 * the app keeps foreground priority: without it the player sank to a background process, got starved of CPU
 * by whatever was on screen (the music stuttered), and could be killed when memory ran low.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val artist = intent?.getStringExtra(EXTRA_ARTIST).orEmpty()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title.ifEmpty { "Apple Music" })
            .setContentText(artist)
            .setContentIntent(open)
            .setOngoing(true)
            .apply {
                @Suppress("DEPRECATION")
                val token = intent?.getParcelableExtra<MediaSession.Token>(EXTRA_TOKEN)
                if (token != null) setStyle(Notification.MediaStyle().setMediaSession(token))
            }
            .build()
        // Always call startForeground first, even when asked to stop: a service started with
        // startForegroundService() that stops without it crashes the app.
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            stopForeground(true)
            stopSelf(startId) // only if no newer "playing" request arrived meanwhile
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 1
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_STOP = "stop"

        fun playing(context: Context, title: String, artist: String, token: MediaSession.Token) {
            runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_TOKEN, token))
            }
        }

        /** Stops through the service itself, so it always calls startForeground before going away. */
        fun stopped(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java).putExtra(EXTRA_STOP, true))
            }
        }
    }
}
