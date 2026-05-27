@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.balch.orpheus

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class OrpheusMediaSessionService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        session = (application as OrpheusApplication).graph.mediaSessionManager.buildMediaSession(this)
        addSession(session!!)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onDestroy() {
        session?.release()
        session = null
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
