package org.balch.orpheus.core.media

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
            // The foreground service is started lazily here — the single moment
            // we actually begin playback — rather than in activate(). This keeps
            // a browse-only bind (Android Auto enumerating the library) from
            // promising a startForeground() we never deliver. startForegroundService
            // → onStartCommand starts the synth engine inside the FGS grace window,
            // and Media3 promotes to foreground when it sees playWhenReady flip
            // true just below.
            if (isPlaying && !doStartService()) {
                // FGS start refused (e.g. a background restriction). Don't leave
                // the app wedged in "Playing" with no service/engine — roll back
                // to a consistent Stopped via the action handler. (Finding 1C.)
                log.warn { "FGS start failed — rolling back activation" }
                rollbackFailedActivation()
                return@post
            }
            // We're (re)entering playback — cancel any armed transient watchdog
            // so a manual resume during a transient loss doesn't get torn down
            // later. (doActivate early-returns when already active, so this is
            // the only place the resume-to-playing path cancels the watchdog.)
            if (isPlaying) mainHandler.removeCallbacks(transientWatchdog)
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

    // Called on the main looper (the AudioFocusController registers its OS focus
    // listener with a main-looper Handler), so run inline rather than re-posting —
    // matching doLossPermanentLocked, which is deliberately inline to avoid a
    // post-vs-GAIN interleave window.
    override fun onPauseTransient() {
        if (!isActive) return
        synthPlayer?.updatePlayState(false)
        // Route through onPauseFromFocusLoss so the controller does NOT
        // signal user-intent back to the focus controller — pausedByTransient
        // must stay set for the next AUDIOFOCUS_GAIN to auto-resume.
        handler?.onPauseFromFocusLoss()
        mainHandler.removeCallbacks(transientWatchdog)
        mainHandler.postDelayed(transientWatchdog, transientWatchdogMs)
    }

    override fun onResumePlayback() {
        if (!isActive) return
        mainHandler.removeCallbacks(transientWatchdog)
        synthPlayer?.updatePlayState(true)
        handler?.onPlay()
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

        // Drive the controller ONLY from genuine external commands, which arrive
        // through SynthPlayer's handle* overrides. A Player.Listener would also
        // fire for our own updatePlaybackState()/updateMetadata() pushes (both
        // call invalidateState()), echoing every self-push back as a fake user
        // play/pause — a ~250 Hz feedback loop that stutters the beat clock.
        player.onSetPlayWhenReady = { playWhenReady ->
            if (playWhenReady) handler?.onPlay() else handler?.onPause()
        }
        player.onStop = { handler?.onStop() }
        player.onPlayFromMediaId = { mediaId -> handler?.onPlayFromMediaId(mediaId) }
        player.onSkipNext = { handler?.onSkipNext() }
        player.onSkipPrevious = { handler?.onSkipPrevious() }
    }

    private fun doActivate() {
        if (isActive) return
        log.info { "Activating media session" }
        ensurePlayer()
        audioFocusController.setListener(this)
        // We're (re)activating — cancel any pending transient watchdog.
        mainHandler.removeCallbacks(transientWatchdog)
        isActive = true
        // NOTE: activation does NOT acquire audio focus or start the foreground
        // service. Focus is taken synchronously by PlaybackController.play() via
        // requestPlaybackFocus() (so a denial rolls back before unmuting), and
        // the FGS is started lazily in updatePlaybackState(true) — the only place
        // we actually begin playback. Activating for a browse-only bind (Android
        // Auto enumerating the library) must NOT grab focus from other apps or
        // promise a startForeground() we'd never deliver while merely browsing.
    }

    // Invariant: by the time this returns, both synthPlayer == null AND
    // isActive == false. The early-return on synthPlayer == null is safe
    // because every teardown path (doDeactivate, doLossPermanentLocked, and
    // updatePlaybackState's FGS-failed rollback) releases the player.
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
        synthPlayer?.onSetPlayWhenReady = null
        synthPlayer?.onStop = null
        synthPlayer?.onPlayFromMediaId = null
        synthPlayer?.release()
        synthPlayer = null
    }

    // Called from updatePlaybackState(true) when the FGS start is refused. Tears
    // down the half-started session and resets the single source of truth so a
    // subsequent play() can retry instead of short-circuiting on a stale Playing
    // state. Mirrors the teardown the old doActivate FGS-failed branch performed,
    // plus the handler?.onStop() reset. (Finding 1C.)
    private fun rollbackFailedActivation() {
        mainHandler.removeCallbacks(transientWatchdog)
        audioFocusController.abandon()
        audioFocusController.setListener(null)
        releaseSynthPlayer()
        isActive = false
        handler?.onStop()
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
