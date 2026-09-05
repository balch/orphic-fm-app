package org.balch.orpheus.core.media

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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

    /**
     * The live session, or null once it has been released. Media services must
     * read this from onGetSession rather than caching their own field: media3
     * feeds that return value straight into addSession, which throws
     * "session is already released" on a stale one.
     */
    val session: MediaSession? get() = mediaSession

    /** [session] narrowed for MediaLibraryService.onGetSession. */
    val librarySession: MediaLibraryService.MediaLibrarySession?
        get() = mediaSession as? MediaLibraryService.MediaLibrarySession

    fun setLibraryCallback(callback: MediaLibraryService.MediaLibrarySession.Callback?) {
        this.libraryCallback = callback
    }

    fun setServiceIntent(intent: Intent) {
        this.serviceIntent = intent
    }

    fun buildLibrarySession(service: MediaLibraryService): MediaLibraryService.MediaLibrarySession {
        releaseStaleSession()
        ensurePlayer()
        val callback = libraryCallback ?: DefaultLibraryCallback()
        val session = MediaLibraryService.MediaLibrarySession.Builder(service, synthPlayer!!, callback)
            .setSessionActivity(launchIntent(service))
            .build()
        mediaSession = session
        return session
    }

    fun buildMediaSession(service: MediaSessionService): MediaSession {
        releaseStaleSession()
        ensurePlayer()
        val session = MediaSession.Builder(service, synthPlayer!!)
            .setSessionActivity(launchIntent(service))
            .build()
        mediaSession = session
        return session
    }

    /**
     * Releases the session and the player it drives. Called ONLY from the owning
     * media service's onDestroy: MediaSession.Builder binds the session to that
     * service, so the service is its real owner. The audio-focus and deactivate
     * paths deliberately pause instead of releasing (see [doLossPermanentLocked]).
     */
    fun releaseSession() {
        // The service is going away, so the FGS is definitively down. Without
        // this, doStartService's isServiceStarted short-circuit would skip the
        // restart and the next play() would find no service and no session.
        isServiceStarted = false
        // isActive deliberately survives: it still gates doDeactivate's focus
        // abandon. doActivate rebuilds the player it drops here (see there).
        if (mediaSession == null && synthPlayer == null) return
        log.info { "Releasing media session" }
        releaseSessionAndPlayer()
    }

    private fun launchIntent(service: android.app.Service): PendingIntent {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(service.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        // Whoever fires this — a TV now-playing tile, the notification, Assistant — is itself in
        // the background, so the start is a background activity launch and the CREATOR of the
        // PendingIntent has to opt in. Without this the system logs
        // "Background activity launch blocked ... balAllowedByPiCreator: BSP.NONE" and the
        // launcher's Open button silently does nothing. Sender-side permission is not enough:
        // from Android 14 the creator's opt-in is required too.
        return PendingIntent.getActivity(
            service,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
            backgroundLaunchOptions(),
        )
    }

    /** Creator-side background-activity-start opt-in; null before the API existed (< 34). */
    private fun backgroundLaunchOptions(): android.os.Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
        } else {
            null
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
    override fun onPauseTransient(): Boolean {
        if (!isActive) return false
        synthPlayer?.updatePlayState(false)
        // Route through onPauseFromFocusLoss so the controller does NOT
        // signal user-intent back to the focus controller — pausedByTransient
        // must stay set for the next AUDIOFOCUS_GAIN to auto-resume. Its return
        // value reports whether this actually interrupted LIVE playback; the
        // focus controller uses it to arm auto-resume only then.
        val wasPlaying = handler?.onPauseFromFocusLoss() ?: false
        // Arm the 10-minute escalation watchdog only when we genuinely paused
        // live playback. A session that was already (user-)paused but still
        // holds focus has nothing to escalate from — and the watchdog would
        // otherwise silently tear down a session the user is intentionally
        // keeping paused.
        // isActive is still true here: we validated it at the top of this method
        // and everything since (onPauseFromFocusLoss is synchronous) runs inline
        // on the main looper, so no queued deactivate() can flip it mid-method.
        // Even if the watchdog later fires after a deactivate, doLossPermanentLocked
        // guards on isActive, so a stale escalation is a no-op.
        if (wasPlaying) {
            mainHandler.removeCallbacks(transientWatchdog)
            mainHandler.postDelayed(transientWatchdog, transientWatchdogMs)
        }
        return wasPlaying
    }

    override fun onResumePlayback() {
        if (!isActive) return
        // Cancel the escalation watchdog SYNCHRONOUSLY here. handler.onPlay()
        // below also reaches updatePlaybackState(true), which cancels it again —
        // but only from a posted runnable. This inline cancel is the one that
        // closes the GAIN-vs-watchdog race; the later one is a harmless backstop.
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
        // Pause, never release: the session is the media service's. Releasing it
        // here left onGetSession handing media3 a dead session, which threw
        // "session is already released" on the next MEDIA_BUTTON start.
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
        //
        // These stay wired across a focus teardown on purpose: a controller press
        // arriving while inactive is the resume path, and it lands on
        // PlaybackController.play(), which re-takes focus and re-activates.
        player.onSetPlayWhenReady = { playWhenReady ->
            if (playWhenReady) handler?.onPlay() else handler?.onPause()
        }
        player.onStop = { handler?.onStop() }
        player.onPlayFromMediaId = { mediaId -> handler?.onPlayFromMediaId(mediaId) }
        player.onSkipNext = { handler?.onSkipNext() }
        player.onSkipPrevious = { handler?.onSkipPrevious() }
    }

    private fun doActivate() {
        // Ahead of the early-return on purpose, and idempotent: the owning
        // service can release the player out from under an already-active
        // manager (its onDestroy calls releaseSession), and every later
        // updatePlaybackState would then land on a null player.
        ensurePlayer()
        if (isActive) return
        log.info { "Activating media session" }
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

    // Invariant: by the time this returns, isActive == false and nothing we own
    // is driving audio. The session and player deliberately survive — they are
    // the media service's, and doStopService() below destroys that service when
    // nothing is bound, which releases them through the owner (onDestroy →
    // releaseSession()). When something IS still bound (Android Auto, a system
    // MediaBrowser, a TV media-button router) the service lives on and so must
    // its session, or the next start intent crashes in addSession.
    private fun doDeactivate() {
        if (!isActive) return
        log.info { "Deactivating media session" }
        mainHandler.removeCallbacks(transientWatchdog)
        synthPlayer?.updatePlayState(false)
        doStopService()
        audioFocusController.abandon()
        audioFocusController.setListener(null)
        isActive = false
    }

    // Drops a session left behind by a previous service instance. A service owns
    // exactly one session, so media3 must never see two live ones for us.
    private fun releaseStaleSession() {
        mediaSession?.release()
        mediaSession = null
    }

    private fun releaseSessionAndPlayer() {
        releaseStaleSession()
        synthPlayer?.onSkipNext = null
        synthPlayer?.onSkipPrevious = null
        synthPlayer?.onSetPlayWhenReady = null
        synthPlayer?.onStop = null
        synthPlayer?.onPlayFromMediaId = null
        synthPlayer?.release()
        synthPlayer = null
    }

    // Called from updatePlaybackState(true) when the FGS start is refused. Drops
    // focus and resets the single source of truth so a subsequent play() can
    // retry instead of short-circuiting on a stale Playing state. Leaves the
    // session intact for the same reason doLossPermanentLocked does. (Finding 1C.)
    private fun rollbackFailedActivation() {
        mainHandler.removeCallbacks(transientWatchdog)
        audioFocusController.abandon()
        audioFocusController.setListener(null)
        synthPlayer?.updatePlayState(false)
        isActive = false
        handler?.onStop()
    }

    /** Returns true if the FGS is up (or was already up, or there is none to
     *  start); false on a thrown ForegroundServiceStartNotAllowedException or
     *  similar. */
    private fun doStartService(): Boolean {
        if (isServiceStarted) return true
        // No intent means the app has no media service and opted out. There is
        // nothing to start, which is success — reading it as a refused FGS start
        // sent updatePlaybackState through rollbackFailedActivation, so play()
        // stopped itself ~13ms later and the app could never sound.
        val intent = serviceIntent ?: return true
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
