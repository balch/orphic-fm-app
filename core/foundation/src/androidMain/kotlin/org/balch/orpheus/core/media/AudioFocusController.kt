package org.balch.orpheus.core.media

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Owns the canonical media AudioAttributes and the AudioFocusRequest used by the
 * synth's playback. Exposes idempotent request()/abandon() and dispatches focus
 * changes to a Listener.
 *
 * On AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK we intentionally do nothing: because the
 * stream is tagged CONTENT_TYPE_MUSIC, the system auto-ducks for us. This avoids
 * mid-note volume jumps from the synth side.
 */
@SingleIn(AppScope::class)
@Inject
class AudioFocusController(
    private val application: Application,
) {

    interface Listener {
        /** Called after a transient loss when focus is regained. Resume playback. */
        fun onResumePlayback()
        /**
         * Called on LOSS_TRANSIENT. Pause if playing; the FGS should stay up.
         * Returns true iff this actually interrupted LIVE playback — the focus
         * controller arms auto-resume only then. A transient loss that lands
         * while already paused (user paused but we still hold focus) returns
         * false so the matching GAIN does NOT resume against the user's intent.
         */
        fun onPauseTransient(): Boolean
        /** Called on LOSS (permanent). Pause, stop FGS, do not auto-resume. */
        fun onLossPermanent()
    }

    private val log = logging("AudioFocus")
    private val audioManager: AudioManager =
        application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var listener: Listener? = null
    private var pausedByTransient: Boolean = false
    @Volatile private var hasFocusFlag: Boolean = false
    val hasFocus: Boolean get() = hasFocusFlag

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocusFlag = true
                if (pausedByTransient) {
                    pausedByTransient = false
                    log.info { "GAIN after transient loss — resuming" }
                    listener?.onResumePlayback()
                } else {
                    log.info { "GAIN (initial or after permanent loss) — no auto-resume" }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocusFlag = false
                // Arm auto-resume ONLY if we actually interrupted live playback.
                // Because we keep holding focus while paused, a transient loss can
                // arrive when the user has already paused (e.g. they hit Pause in
                // the widget, then another app's autoplay video grabs focus). In
                // that case onPauseTransient() returns false and we must NOT arm
                // pausedByTransient — otherwise the matching AUDIOFOCUS_GAIN would
                // resume playback against the user's intent.
                val wasPlaying = listener?.onPauseTransient() ?: false
                // Latch, don't assign: once a transient that interrupted live
                // playback has armed auto-resume, a SECOND transient arriving
                // before the GAIN (we're now already paused, so onPauseTransient
                // returns false) must NOT disarm it — otherwise the matching
                // AUDIOFOCUS_GAIN would leave the user's music silenced.
                if (wasPlaying) pausedByTransient = true
                log.info {
                    if (wasPlaying) "LOSS_TRANSIENT — paused live playback, will auto-resume on GAIN"
                    else "LOSS_TRANSIENT while not playing — no auto-resume armed"
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // No app action — system auto-ducks because CONTENT_TYPE_MUSIC.
                log.info { "LOSS_TRANSIENT_CAN_DUCK — letting system auto-duck" }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocusFlag = false
                pausedByTransient = false
                log.info { "LOSS (permanent) — pausing, stopping FGS, no auto-resume" }
                listener?.onLossPermanent()
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .build()

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Returns true if focus was GRANTED. Idempotent — safe to call repeatedly. */
    @Synchronized
    fun request(): Boolean {
        if (hasFocusFlag) return true
        val result = audioManager.requestAudioFocus(focusRequest)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasFocusFlag = granted
        if (granted) {
            // We now own focus again. The OS does not deliver an
            // AUDIOFOCUS_GAIN callback for the initial grant, so clear the
            // transient flag here — otherwise a later real GAIN (from a
            // different transient cycle) would mis-fire auto-resume.
            pausedByTransient = false
        }
        log.info { "requestAudioFocus → ${if (granted) "GRANTED" else "DENIED ($result)"}" }
        return granted
    }

    @Synchronized
    fun abandon() {
        if (!hasFocusFlag && !pausedByTransient) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocusFlag = false
        pausedByTransient = false
        log.info { "abandonAudioFocusRequest" }
    }

    /**
     * Signal that the user explicitly paused (or stopped) while we may be in a
     * transient-loss state. Clears pausedByTransient so a later
     * AUDIOFOCUS_GAIN does not auto-resume against the user's intent.
     */
    @Synchronized
    fun notifyUserPaused() {
        if (pausedByTransient) {
            log.info { "notifyUserPaused — clearing pausedByTransient" }
            pausedByTransient = false
        }
    }
}
