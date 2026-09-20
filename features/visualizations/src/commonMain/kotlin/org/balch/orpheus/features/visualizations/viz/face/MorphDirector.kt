package org.balch.orpheus.features.visualizations.viz.face

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * @param morph 0 = first keyframe, 1 = last
 * @param flicker kick flash, pushes the dissolve ahead for a few frames
 * @param glitch drum-hit chroma split and row tear
 * @param crt scanline strength
 * @param mono 0 = colour, 1 = black and white. Binary today, a Float so it can be eased later.
 */
data class MorphFrame(
    val morph: Float,
    val flicker: Float,
    val glitch: Float,
    val crt: Float,
    val mono: Float,
)

/**
 * A NaN or +/-Inf from a hand-written curve must not stick in the ratchet forever; [floor]
 * only ever moves up, so one bad sample would otherwise pin the face at that height for good.
 */
internal fun ratchet(floor: Float, shaped: Float): Float =
    max(floor, if (shaped.isFinite()) shaped.coerceIn(0f, 1f) else floor)

/** Same guard for the additive surge: a non-finite result contributes nothing this frame. */
internal fun surged(base: Float, surge: Float): Float =
    (base + if (surge.isFinite()) surge else 0f).coerceIn(0f, 1f)

/** Turns song position and live levels into what the face shader draws. Not thread-safe. */
class MorphDirector {
    private var intensity = 0f
    private var floor = 0f
    private var lastProgress = 0f
    private var flicker = 0f
    private var glitch = 0f
    private var prevKick = 0f
    private var drumTrough = 0f
    private var drumPeak = 0f
    private var sinceFlash = MIN_FLASH_GAP_S
    private var sinceGlitch = MIN_FLASH_GAP_S
    private var monoHold = 0f
    private var monoLatched = false

    fun reset() {
        intensity = 0f; floor = 0f; lastProgress = 0f
        flicker = 0f; glitch = 0f; prevKick = 0f; drumTrough = 0f; drumPeak = 0f
        sinceFlash = MIN_FLASH_GAP_S; sinceGlitch = MIN_FLASH_GAP_S
        monoHold = 0f; monoLatched = false
    }

    fun update(
        dt: Float,
        progress: Float,
        level: Float,
        kick: Float,
        drums: Float,
        floorOffset: Float = 0f,
    ): MorphFrame {
        // A stray NaN/Inf anywhere upstream (a flaky viz flow, a bad seek estimate) must not
        // propagate into state that persists across frames.
        val safeDt = (if (dt.isFinite()) dt else 0f).coerceIn(0f, 0.1f)
        val safeProgress = if (progress.isFinite()) progress else 0f
        val safeLevel = if (level.isFinite()) level else 0f
        val safeKick = if (kick.isFinite()) kick else 0f
        val safeDrums = if (drums.isFinite()) drums else 0f

        val p = safeProgress.coerceIn(0f, 1f)
        // Small backward steps are the seek bar re-estimating its end; a big one is a new song.
        if (p < lastProgress - NEW_SONG_DROP) reset()
        lastProgress = p

        // Slow follower: a chorus surges the face, one loud hit does not.
        val target = safeLevel.coerceIn(0f, 1f)
        val tau = if (target > intensity) ATTACK_S else RELEASE_S
        intensity += (target - intensity) * (1f - exp(-safeDt / tau))

        floor = ratchet(floor, shape(p))
        // The knob sits outside the ratchet so turning it back down takes effect.
        val base = (floor + floorOffset).coerceIn(0f, 1f)
        val morph = surged(base, surge(intensity, base))

        sinceFlash += safeDt
        sinceGlitch += safeDt
        monoHold = max(0f, monoHold - safeDt)
        flicker *= exp(-safeDt / FLICKER_DECAY_S)
        glitch *= exp(-safeDt / GLITCH_DECAY_S)
        // An exponential decay never reaches literal zero; below one 8-bit step nothing on
        // screen changes anyway, and TvPictureEffect relies on exact zero to switch its pass off.
        if (flicker < VISIBLE_EPSILON) flicker = 0f
        if (glitch < VISIBLE_EPSILON) glitch = 0f
        // The gap keeps full-frame flashes under 3 Hz whatever the kick pattern does.
        if (safeKick - prevKick > ONSET_DELTA && sinceFlash >= MIN_FLASH_GAP_S) {
            flicker = FLICKER_PEAK
            sinceFlash = 0f
            // Riding the same gate is what keeps the colour switch inside the 3 Hz bound.
            if (morph > GLIMPSE_MIN_MORPH) {
                monoHold = GLIMPSE_MIN_S + (GLIMPSE_MAX_S - GLIMPSE_MIN_S) * base
            }
        }
        // A hit is a rise above the recent trough, judged against how loud the drums have been
        // lately: a quiet intro's taps count, the same tap under a loud chorus is bleed.
        drumTrough = if (safeDrums < drumTrough) safeDrums
            else drumTrough + (safeDrums - drumTrough) * (1f - exp(-safeDt / DRUM_TROUGH_RISE_S))
        drumPeak = max(safeDrums, drumPeak * exp(-safeDt / DRUM_PEAK_DECAY_S))
        val rise = safeDrums - drumTrough
        val hitThreshold = max(DRUM_ONSET_MIN, DRUM_ONSET_SHARE * drumPeak)
        // Same gap as the kick flash: the row tear is a full-picture change too.
        if (rise > hitThreshold && sinceGlitch >= MIN_FLASH_GAP_S) {
            val strength = (rise / max(drumPeak, DRUM_ONSET_MIN)).coerceIn(0f, 1f)
            glitch = max(glitch, GLITCH_FLOOR + (1f - GLITCH_FLOOR) * strength)
            sinceGlitch = 0f
            // Re-arm only after the level has fallen away again, so a held note is one hit.
            drumTrough = safeDrums
        }
        prevKick = safeKick

        // The song opens on a normal face, so kicks show nothing until it has started to turn;
        // internal `flicker` stays unscaled so its own decay/cap logic above is unaffected.
        // A latch, not a live comparison: base carries the MIDI-bindable DECAY knob, and one LSB
        // of jitter either side of the threshold would otherwise switch the whole picture every
        // frame. Only reset() clears it, which is also what "for good" means.
        if (base >= MONO_PERMANENT) monoLatched = true

        val visibleFlicker = flicker * min(1f, morph * 8f)
        return MorphFrame(
            morph = morph,
            flicker = visibleFlicker,
            glitch = glitch,
            crt = CRT_BASE + (1f - CRT_BASE) * intensity,
            mono = if (monoLatched || monoHold > 0f) 1f else 0f,
        )
    }

    companion object {
        const val ATTACK_S = 0.3f
        const val RELEASE_S = 2.5f
        const val FLICKER_DECAY_S = 0.08f
        const val GLITCH_DECAY_S = 0.16f
        const val FLICKER_PEAK = 0.6f
        const val MIN_FLASH_GAP_S = 0.34f
        const val ONSET_DELTA = 0.15f

        /** A detected drum hit never glitches weaker than this, however soft it was. */
        const val GLITCH_FLOOR = 0.7f

        /** Smallest rise that can count as a drum hit; below it is the noise floor. */
        const val DRUM_ONSET_MIN = 0.025f

        /** A hit must rise by this share of the recent loudest hit. Lower catches more. */
        const val DRUM_ONSET_SHARE = 0.35f

        /** How long the recent-loudest-hit memory lasts; sets how fast it adapts to a quiet part. */
        const val DRUM_PEAK_DECAY_S = 2f
        const val DRUM_TROUGH_RISE_S = 0.25f
        const val NEW_SONG_DROP = 0.25f
        const val CRT_BASE = 0.35f

        /** Below one 8-bit step, a value nothing on screen shows either; see the decay above. */
        const val VISIBLE_EPSILON = 1f / 255f

        /** Below this the face is still essentially human, so a glimpse would read as a fault. */
        const val GLIMPSE_MIN_MORPH = 1f / 16f
        const val GLIMPSE_MIN_S = 0.08f
        const val GLIMPSE_MAX_S = 1.5f

        /** Past this much of the turn the broadcast never comes back to colour. */
        const val MONO_PERMANENT = 0.66f
    }
}
