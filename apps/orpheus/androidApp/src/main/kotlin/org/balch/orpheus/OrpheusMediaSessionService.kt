@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.balch.orpheus

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class OrpheusMediaSessionService : MediaSessionService() {

    private val mediaSessionManager
        get() = (application as OrpheusApplication).graph.mediaSessionManager

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        addSession(mediaSessionManager.buildMediaSession(this))
    }

    // Reads the manager's live handle rather than a cached field. Media3 calls
    // this from onStartCommand for MEDIA_BUTTON intents and feeds the result
    // straight to addSession, which throws on a released session.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSessionManager.session

    override fun onDestroy() {
        // The service built the session, so the service releases it.
        runCatching { mediaSessionManager.releaseSession() }
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            "Orpheus",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
