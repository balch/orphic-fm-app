package org.balch.orpheus.core.media

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager(
    private val application: Application,
) {
    private val log = logging("MediaSessionManager")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handler: MediaSessionActionHandler? = null
    private var mediaSession: MediaSession? = null
    private var synthPlayer: SynthPlayer? = null
    private var libraryCallback: MediaLibraryService.MediaLibrarySession.Callback? = null
    private var serviceIntent: Intent? = null
    private var isActive = false
    private var isServiceStarted = false

    val session: MediaSession? get() = mediaSession

    fun setLibraryCallback(callback: MediaLibraryService.MediaLibrarySession.Callback?) {
        this.libraryCallback = callback
    }

    fun setServiceIntent(intent: Intent) {
        this.serviceIntent = intent
    }

    fun buildLibrarySession(service: MediaLibraryService): MediaLibraryService.MediaLibrarySession {
        ensurePlayer()
        val callback = libraryCallback ?: DefaultLibraryCallback()
        val session = MediaLibraryService.MediaLibrarySession.Builder(service, synthPlayer!!, callback)
            .setSessionActivity(launchIntent(service))
            .build()
        mediaSession = session
        isActive = true
        return session
    }

    fun buildMediaSession(service: MediaSessionService): MediaSession {
        ensurePlayer()
        val session = MediaSession.Builder(service, synthPlayer!!)
            .setSessionActivity(launchIntent(service))
            .build()
        mediaSession = session
        isActive = true
        return session
    }

    private fun launchIntent(service: android.app.Service): PendingIntent {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(service.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        return PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    actual fun activate() {
        mainHandler.post { doActivate() }
    }

    actual fun deactivate() {
        mainHandler.post { doDeactivate() }
    }

    actual fun updatePlaybackState(isPlaying: Boolean) {
        mainHandler.post {
            if (!isActive) return@post
            synthPlayer?.updatePlayState(isPlaying)
        }
    }

    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }

    actual fun updateMetadata(metadata: PlaybackMetadata) {
        mainHandler.post {
            if (!isActive) return@post
            synthPlayer?.updateMetadata(
                title = metadata.title,
                subtitle = metadata.subtitle,
                artworkData = metadata.artworkPng,
            )
            synthPlayer?.updatePlayState(metadata.isPlaying)
        }
    }

    private fun ensurePlayer() {
        if (synthPlayer != null) return
        val player = SynthPlayer()
        synthPlayer = player

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) handler?.onPlay() else handler?.onPause()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                handler?.onPlayFromMediaId(mediaId)
            }
        })

        player.onSkipNext = { handler?.onSkipNext() }
        player.onSkipPrevious = { handler?.onSkipPrevious() }
    }

    private fun doActivate() {
        if (isActive) return
        log.info { "Activating media session" }
        ensurePlayer()
        isActive = true
        doStartService()
    }

    private fun doDeactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }
        doStopService()
        mediaSession?.release()
        mediaSession = null
        synthPlayer?.onSkipNext = null
        synthPlayer?.onSkipPrevious = null
        synthPlayer = null
        isActive = false
    }

    private fun doStartService() {
        if (isServiceStarted) return
        serviceIntent?.let {
            log.info { "Starting media service" }
            ContextCompat.startForegroundService(application, it)
            isServiceStarted = true
        }
    }

    private fun doStopService() {
        if (!isServiceStarted) return
        serviceIntent?.let {
            log.info { "Stopping media service" }
            application.stopService(it)
            isServiceStarted = false
        }
    }

    private class DefaultLibraryCallback : MediaLibraryService.MediaLibrarySession.Callback
}
