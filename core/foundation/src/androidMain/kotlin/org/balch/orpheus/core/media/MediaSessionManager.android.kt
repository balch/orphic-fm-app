package org.balch.orpheus.core.media

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@UnstableApi
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager(
    private val application: Application,
    private val audioFocusController: AudioFocusController,
) : AudioFocusController.Listener {
    private val log = logging("MediaSessionManager")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handler: MediaSessionActionHandler? = null
    private var mediaSession: MediaSession? = null
    private var synthPlayer: SynthPlayer? = null
    private var libraryCallback: MediaLibraryService.MediaLibrarySession.Callback? = null
    private var serviceIntent: Intent? = null
    private var isActive = false
    private var isServiceStarted = false

    // Android 17: keep FGS up across a transient loss for up to 10 minutes,
    // then force a permanent-loss path so the FGS doesn't sit idle forever.
    // Calls the body directly (not onLossPermanent) so we don't post a second
    // runnable that could interleave with a late AUDIOFOCUS_GAIN callback.
    private val transientWatchdog = Runnable {
        log.warn { "Transient loss exceeded 10 minutes — escalating to permanent loss" }
        doLossPermanentLocked()
    }
    private val transientWatchdogMs = 10L * 60L * 1000L

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
        return session
    }

    fun buildMediaSession(service: MediaSessionService): MediaSession {
        ensurePlayer()
        val session = MediaSession.Builder(service, synthPlayer!!)
            .setSessionActivity(launchIntent(service))
            .build()
        mediaSession = session
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

    /**
     * Synchronous focus request — must be called BEFORE muteSink unmutes in
     * play(), so a denied request rolls back cleanly. Idempotent via the
     * AudioFocusController's hasFocusFlag short-circuit.
     */
    actual fun requestPlaybackFocus(): Boolean = audioFocusController.request()

    actual fun notifyUserPaused() {
        audioFocusController.notifyUserPaused()
        // User has acknowledged the paused state — cancel the transient
        // escalation watchdog so it doesn't tear down a session the user is
        // intentionally keeping paused.
        mainHandler.post { mainHandler.removeCallbacks(transientWatchdog) }
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

    // ── AudioFocusController.Listener ──────────────────────────────

    override fun onPauseTransient() {
        mainHandler.post {
            if (!isActive) return@post
            synthPlayer?.updatePlayState(false)
            // Route through onPauseFromFocusLoss so the controller does NOT
            // signal user-intent back to the focus controller — pausedByTransient
            // must stay set for the next AUDIOFOCUS_GAIN to auto-resume.
            handler?.onPauseFromFocusLoss()
            mainHandler.removeCallbacks(transientWatchdog)
            mainHandler.postDelayed(transientWatchdog, transientWatchdogMs)
        }
    }

    override fun onResumePlayback() {
        mainHandler.post {
            if (!isActive) return@post
            mainHandler.removeCallbacks(transientWatchdog)
            synthPlayer?.updatePlayState(true)
            handler?.onPlay()
        }
    }

    override fun onLossPermanent() {
        mainHandler.post { doLossPermanentLocked() }
    }

    /**
     * Runs the permanent-loss teardown synchronously on the main thread.
     * Callable from the focus listener path (via onLossPermanent's post) AND
     * directly from the transientWatchdog (which already runs on main). Calling
     * inline from the watchdog eliminates a post-vs-GAIN interleave window in
     * which a late AUDIOFOCUS_GAIN callback could enqueue an onResumePlayback
     * runnable that flickers playback back on before this teardown drained.
     */
    private fun doLossPermanentLocked() {
        if (!isActive) return
        mainHandler.removeCallbacks(transientWatchdog)
        synthPlayer?.updatePlayState(false)
        // System-driven pause, not user-driven — keep the controller's
        // user-intent signal clear. (pausedByTransient is already cleared by
        // AudioFocusController on AUDIOFOCUS_LOSS, and abandon() below clears
        // it for the watchdog-escalation path.)
        handler?.onPauseFromFocusLoss()
        doStopService()
        audioFocusController.abandon()
        audioFocusController.setListener(null)
        // Release the player + receiver — leaving them registered after a
        // permanent loss would keep the volume BroadcastReceiver firing
        // invalidateState() on a session that no longer drives audio, and the
        // attached Player.Listener could still bounce stale onPlay/onPause
        // through handler when reused.
        releaseSynthPlayer()
        isActive = false
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun ensurePlayer() {
        if (synthPlayer != null) return
        val player = SynthPlayer(application)
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
        val wasActive = isActive
        if (!wasActive) {
            log.info { "Activating media session" }
            ensurePlayer()
            audioFocusController.setListener(this)
        }
        // ALWAYS re-request focus — even when already active. After a transient
        // loss isActive stays true but hasFocusFlag is false; without this call
        // a user-initiated play during the transient state would never re-take
        // focus. request() is idempotent (early-returns on hasFocusFlag=true).
        if (!audioFocusController.request()) {
            log.warn { "Audio focus DENIED — aborting activation (Android 17 would silently fail playback)" }
            if (!wasActive) {
                // Symmetric cleanup for failed initial activation.
                audioFocusController.setListener(null)
                releaseSynthPlayer()
            }
            return
        }
        // Refocus succeeded — cancel any pending transient watchdog so it
        // doesn't fire later and tear down a session we just brought back up.
        mainHandler.removeCallbacks(transientWatchdog)
        if (wasActive) return
        isActive = true
        if (!doStartService()) {
            // Most likely cause: background restriction on Android 12+ when a
            // MediaBrowser bind transitively triggered activation without an
            // FGS-launch exemption. Roll back so we don't sit in an "active"
            // state with no foreground service backing the audio.
            log.warn { "doStartService failed — rolling back activation" }
            audioFocusController.abandon()
            audioFocusController.setListener(null)
            releaseSynthPlayer()
            isActive = false
        }
    }

    // Invariant: by the time this returns, both synthPlayer == null AND
    // isActive == false. The early-return on synthPlayer == null is safe
    // because every teardown path (doDeactivate, doLossPermanentLocked, and
    // doActivate's focus-denied / FGS-failed bail-outs) releases the player.
    private fun doDeactivate() {
        if (synthPlayer == null) return
        log.info { "Deactivating media session" }
        mainHandler.removeCallbacks(transientWatchdog)
        if (isActive) {
            doStopService()
            audioFocusController.abandon()
        }
        audioFocusController.setListener(null)
        releaseSynthPlayer()
        isActive = false
    }

    private fun releaseSynthPlayer() {
        mediaSession?.release()
        mediaSession = null
        synthPlayer?.onSkipNext = null
        synthPlayer?.onSkipPrevious = null
        synthPlayer?.release()
        synthPlayer = null
    }

    /** Returns true if the FGS is up (or was already up); false on a thrown
     *  ForegroundServiceStartNotAllowedException or similar. */
    private fun doStartService(): Boolean {
        if (isServiceStarted) return true
        val intent = serviceIntent ?: return false
        return try {
            log.info { "Starting media service" }
            ContextCompat.startForegroundService(application, intent)
            isServiceStarted = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when a
            // backgrounded bind triggers activation. Don't crash the process —
            // the caller will roll back state.
            log.warn(e) { "startForegroundService failed (likely background restriction)" }
            false
        }
    }

    private fun doStopService() {
        if (!isServiceStarted) return
        serviceIntent?.let {
            log.info { "Stopping media service" }
            try {
                application.stopService(it)
            } catch (e: Exception) {
                log.warn(e) { "stopService threw — ignoring" }
            }
            isServiceStarted = false
        }
    }

    private class DefaultLibraryCallback : MediaLibraryService.MediaLibrarySession.Callback
}
