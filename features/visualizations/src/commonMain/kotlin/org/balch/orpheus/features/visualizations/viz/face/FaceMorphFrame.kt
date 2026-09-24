package org.balch.orpheus.features.visualizations.viz.face

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.math.max
import kotlin.math.min

/** Song position when Pulsar has one, else a fixed four-minute turn from activation. */
internal const val FALLBACK_TURN_S = 240f

/** Which keyframe pair to draw and how far between them. */
internal data class StageBlend(val stage: Int, val t: Float)

/**
 * How far into the final keyframe pair the turn is allowed to go; 1 shows the last keyframe whole.
 * [buildLastStageBiasMap] turns the jaw first and the eyes last, so holding short of 1 ends on the
 * new teeth while the eyes are still arriving. FaceMorphRenderHarness renders the candidates.
 */
internal const val LAST_STAGE_CAP = 0.8f

/**
 * Requires [frameCount] >= 2. Non-finite or out-of-range [morph] is treated as clamped 0..1.
 * [lastStageCap] limits only the final pair, so every earlier stage still runs its full length.
 */
internal fun stageBlend(morph: Float, frameCount: Int, lastStageCap: Float = 1f): StageBlend {
    require(frameCount >= 2) { "need at least two keyframes, got $frameCount" }
    val safeMorph = (if (morph.isFinite()) morph else 0f).coerceIn(0f, 1f)
    val cap = (if (lastStageCap.isFinite()) lastStageCap else 1f).coerceIn(0f, 1f)
    val position = min(safeMorph * (frameCount - 1), frameCount - 2 + cap)
    val stage = position.toInt().coerceAtMost(frameCount - 2)
    return StageBlend(stage, position - stage)
}

/** Null when there is no progress to report or the duration is unusable; else clamped 0..1. */
internal fun songFraction(progress: PlaybackProgress?): Float? {
    if (progress == null || progress.durationMs <= 0) return null
    return (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
}

/** Cyclic fallback clock: wraps every [FALLBACK_TURN_S], so a section-less vibe keeps turning. */
internal fun fallbackFraction(elapsed: Float): Float {
    val safeElapsed = if (elapsed.isFinite()) elapsed else 0f
    val wrapped = safeElapsed % FALLBACK_TURN_S
    return (if (wrapped < 0f) wrapped + FALLBACK_TURN_S else wrapped) / FALLBACK_TURN_S
}

/** Scales a 0..1 signal by the SIGNAL knob (0..1) with headroom to hit full strength at 0.5. */
internal fun signalScaled(value: Float, signalKnob: Float): Float =
    (value * signalKnob * 2f).coerceIn(0f, 1f)

/** Tracks 0 to 2 are the percussion band: kick, then the rest of the kit. */
private const val DRUM_TRACKS = 3

/**
 * The loudest drum right now. The kick counts: early in a song it is often the only drum, and
 * the glitch is the one effect allowed on a face that has not started to turn.
 */
internal fun drumLevel(trackLevels: FloatArray): Float {
    var loudest = 0f
    for (i in 0 until minOf(DRUM_TRACKS, trackLevels.size)) {
        val v = trackLevels[i]
        if (v.isFinite() && v > loudest) loudest = v
    }
    return loudest.coerceAtMost(1f)
}

/** Below this much remaining, rebasing onto it would turn the face in seconds; use the fallback. */
private const val MIN_REMAINING_FOR_REBASE = 0.05f

/** How long a song change waits for progressFlow to report a value other than the stale one. */
private const val STALE_TIMEOUT_S = 1.0f

/** How long a mid-song null reading is held at the last fraction before falling back to a clock. */
private const val NULL_GRACE_S = 1.0f

/**
 * Where in the song the current turn started, so the face always opens human and spans only
 * what is left of the song from there. Owns the fallback clock, restarting it on activation and
 * on every song change so a section-less vibe keeps turning from a human face too. Not thread-safe.
 */
internal class TurnOrigin {
    private var origin = 0f
    private var usingFallback = true
    private var fallbackElapsed = 0f

    // Set only when the fallback clock is running because real progress was lost mid-song, not
    // because this song/vibe never had any: it clamps at 1 instead of wrapping, so a dropout
    // never snaps the face back to human. See fraction()'s "ticking fallback" section.
    private var droppedOut = false

    // Last value fraction() returned. Doubles as the freeze point for a hold and the seed for a
    // dropout's fallback clock, so re-entering either is always continuous with what was drawn.
    private var lastFraction = 0f

    // Frozen hold: output pinned at holdValue until a usable reading resumes the turn. Used both
    // for the brief window right after a null reading (inGrace counts down toward the fallback
    // clock) and for "the song is still behind where the face already is" (no countdown; waits
    // indefinitely for a reading that catches up).
    private var holding = false
    private var holdValue = 0f
    private var inGrace = false
    private var graceElapsed = 0f

    // Watches for a reading that differs from recoveryReference while usingFallback, so a
    // fallback stint that started from real progress (a stale post-song-change wait, or a
    // mid-song dropout once its grace expires) can re-adopt it. A fallback that started from
    // having no progress at all (activation/song-change with nothing to wait out, or too little
    // of the song left to animate) never sets this, so it just keeps wrapping undisturbed.
    private var awaitingRecovery = false
    private var recoveryReference: PlaybackProgress? = null

    // A song change's progressFlow reading can still be the OLD song's for up to one poll; hold
    // at 0 until it moves off that stale value (or a timeout forces the issue) instead of rebasing
    // onto it.
    private var awaitingFreshProgress = false
    private var staleProgress: PlaybackProgress? = null
    private var sinceSongChange = 0f

    fun onActivate(progress: PlaybackProgress?) {
        awaitingFreshProgress = false
        rebase(progress)
    }

    fun onSongChange(progress: PlaybackProgress?) {
        sinceSongChange = 0f
        staleProgress = progress
        holding = false
        inGrace = false
        awaitingRecovery = false
        recoveryReference = null
        if (progress == null) {
            // Nothing stale to wait out: a null reading already means "no position data".
            awaitingFreshProgress = false
            rebase(null)
        } else {
            awaitingFreshProgress = true
            droppedOut = false
        }
    }

    fun fraction(progress: PlaybackProgress?, dt: Float): Float {
        val safeDt = (if (dt.isFinite()) dt else 0f).coerceIn(0f, 0.1f)

        if (awaitingFreshProgress) {
            sinceSongChange += safeDt
            when {
                progress != staleProgress -> {
                    awaitingFreshProgress = false
                    rebase(progress)
                }
                sinceSongChange >= STALE_TIMEOUT_S -> {
                    // Don't rebase onto the very reading just declared unusable: it may never
                    // move off that value. Run the fallback from zero instead and keep watching
                    // for a genuinely different reading to re-adopt below.
                    awaitingFreshProgress = false
                    usingFallback = true
                    droppedOut = false
                    origin = 0f
                    fallbackElapsed = 0f
                    awaitingRecovery = true
                    recoveryReference = staleProgress
                }
                else -> return record(0f)
            }
            // Fall through: compute this frame from the state just established above.
        }

        if (holding) {
            val frac = songFraction(progress)
            if (frac != null) {
                if (holdValue < 1f && frac >= holdValue) {
                    holding = false
                    inGrace = false
                    adopt(frac, holdValue)
                    return record(effective(frac))
                }
                // Real but still-behind (or already terminal) data: stop the grace countdown,
                // the song is no longer silent, just not there yet. Keep waiting for it.
                inGrace = false
                return record(holdValue)
            }
            if (inGrace) {
                graceElapsed += safeDt
                if (graceElapsed >= NULL_GRACE_S) {
                    // Grace exhausted: keep turning on our own clock, seeded so the value stays
                    // continuous. A song that had real progress never wraps back to human for
                    // this, unlike a genuinely section-less vibe (see the fallback block below).
                    holding = false
                    inGrace = false
                    usingFallback = true
                    droppedOut = true
                    fallbackElapsed = holdValue * FALLBACK_TURN_S
                    awaitingRecovery = true
                    recoveryReference = null
                    // Fall through to the ticking-fallback computation below.
                } else {
                    return record(holdValue)
                }
            } else {
                return record(holdValue)
            }
        }

        if (usingFallback) {
            fallbackElapsed += safeDt
            val heldValue = if (droppedOut) {
                // A dropped-out turn holds at 1 for good; it never restarts on its own.
                (fallbackElapsed / FALLBACK_TURN_S).coerceIn(0f, 1f)
            } else {
                fallbackFraction(fallbackElapsed)
            }
            if (heldValue < 1f && awaitingRecovery && progress != recoveryReference) {
                recoveryReference = progress
                val frac = songFraction(progress)
                if (frac != null) {
                    awaitingRecovery = false
                    if (frac >= heldValue) {
                        adopt(frac, heldValue)
                        return record(effective(frac))
                    }
                    holding = true
                    inGrace = false
                    holdValue = heldValue
                    return record(heldValue)
                }
                // Still nothing usable; keep watching against this new reference.
            }
            return record(heldValue)
        }

        // Normal following.
        val frac = songFraction(progress)
        if (frac == null) {
            // Progress dropped out mid-song: hold briefly before falling back to a clock.
            holding = true
            inGrace = true
            graceElapsed = 0f
            holdValue = lastFraction
            return record(lastFraction)
        }
        // The raw fraction can step backward without ever going null (a duration re-estimate,
        // a live tempo re-measurement): ratchet so the turn itself never runs backward mid-song.
        // Only onActivate/onSongChange (via rebase(), which zeroes lastFraction) release this.
        return record(max(lastFraction, effective(frac)))
    }

    /** Re-enters normal following so [frac] maps to exactly [held] this frame: no jump. */
    private fun adopt(frac: Float, held: Float) {
        usingFallback = false
        droppedOut = false
        origin = if (held <= 0f) frac else (frac - held) / (1f - held)
    }

    private fun effective(frac: Float): Float {
        val denom = 1f - origin
        if (denom <= 0f) return 1f
        val eff = (frac - origin) / denom
        return if (eff.isFinite()) eff.coerceIn(0f, 1f) else 0f
    }

    private fun record(value: Float): Float {
        lastFraction = value
        return value
    }

    private fun rebase(progress: PlaybackProgress?) {
        val frac = songFraction(progress)
        if (frac == null || frac > 1f - MIN_REMAINING_FOR_REBASE) {
            usingFallback = true
            origin = 0f
        } else {
            usingFallback = false
            origin = frac
        }
        fallbackElapsed = 0f
        droppedOut = false
        holding = false
        inGrace = false
        awaitingRecovery = false
        recoveryReference = null
        lastFraction = 0f
    }
}
