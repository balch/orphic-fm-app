package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * One note in a bass lick pattern.
 * @param scaleDegree Index into the current scale (0 = root, 1 = 2nd degree, etc.).
 *   Degrees at or above the scale's size wrap with an octave bump, so in a 6-note
 *   hexatonic scale degree 6 is the root an octave up — that is how a figure reaches a
 *   higher register without moving [Lick]'s octave.
 *
 *   **A NEGATIVE degree is a REST** for this step's full [duration], not a note below
 *   the root. It is the only way to author silence inside a figure ([Lick.loopLength]
 *   only pads silence onto the end), so a call-and-response reply opens with one.
 *   At high [Vibe.lickMutation] a rest can still turn into a note, with probability
 *   `mutation * 0.3` per cycle; set mutation to 0 for a rest that never moves.
 * @param duration Note length in beats (0.25 = 16th, 0.5 = 8th, 1.0 = quarter)
 * @param velocity Hit strength 0-1 (lower = ghost note feel)
 * @param glideRate Optional per-note portamento. `-1f` (default) = use the active
 *   voice's own [OrpheusEngine.glideRate]. A value in `0f..1f` overrides for this step only:
 *   0 = instant pitch jump; 0.3 = smooth; 0.6+ = very slow slide. The sentinel
 *   matches the C++ engine's `glide_rate` convention exactly (no boxing, no
 *   marshalling translation).
 * @param hitProbability Chance this note fires at all, before tension lifts it. `1f`
 *   (default) always fires. Tension raises it toward certainty as the section climbs:
 *   `effective = hitProbability + (1 - hitProbability) * tensionIntensity`, so `0.3f`
 *   fires about a third of the time at rest and every time at peak tension. A note that
 *   loses its roll drops whole, taking its hold steps with it, and the note after it is
 *   struck clean rather than slid into, since there is nothing to glide from.
 *
 *   Honored on all three authored channels — [Vibe.lick], [Vibe.bassLine] and the
 *   [LickRotation] pool. Each carries it in a port block parallel to its own 4-float
 *   step transport, rather than as a fifth field in that stride, so existing step
 *   indices mean the same thing on both sides of the bridge.
 */
@Serializable
data class LickStep(
    val scaleDegree: Int,
    val duration: Float,
    val velocity: Float = 0.8f,
    val glideRate: Float = -1f,
    val hitProbability: Float = 1f,
)

/**
 * A repeating melodic figure (bass riff). Assign to a [Vibe] and set
 * `lickMode = LickMode.Fill` (or `Squash`) on the track that should play it.
 * @param steps The note sequence. Max 64 steps.
 * @param loopLength Total loop length in **beats**. When larger than the sum of step
 *   durations, the extra time is silence (rest padding). E.g. a 4-beat lick with
 *   `loopLength = 8` plays 4 beats of notes then 4 beats of rest per cycle.
 *   Default (0) = no rest, notes fill the entire pattern.
 */
@Serializable
data class Lick(
    val steps: List<LickStep>,
    val loopLength: Int = 0,
) {
    init {
        require(steps.size <= MAX_LICK_STEPS) {
            "Lick steps size ${steps.size} exceeds MAX_LICK_STEPS=$MAX_LICK_STEPS"
        }
        require(steps.all { it.hitProbability in 0f..1f }) {
            "Lick hitProbability must be in 0f..1f, got ${steps.map { it.hitProbability }}"
        }
        require(steps.all { it.glideRate == -1f || it.glideRate in 0f..1f }) {
            "LickStep.glideRate must be -1 (use track default) or in 0..1, got " +
                steps.map { it.glideRate }
        }
    }

    companion object {
        const val MAX_LICK_STEPS = 64

        /** Fields marshalled per lick step to C++: degree, duration, velocity, glide. */
        const val LICK_FIELDS_PER_STEP = 4
    }
}

/**
 * How a track maps the vibe's [Lick] to sequencer steps.
 * Only meaningful when [TrackVoice.role] is [TrackRole.Melodic].
 */
@Serializable
sealed class LickMode {
    /** No lick — track uses generative patterns. */
    @Serializable
    data object None : LickMode()

    /** Compress lick to fit within one bar. In multi-bar (32/64-step) mode, the
     *  second half is handled by the bar strategy. */
    @Serializable
    data object Squash : LickMode()

    /** Lick spans the full step count as a single continuous phrase.
     *  Bypasses the bar 1/bar 2 split entirely. */
    @Serializable
    data object Fill : LickMode()
}

/**
 * A pool of licks a vibe rotates between. When set on [Vibe.lickRotation], the engine picks a
 * [pool] member per section, so the riff varies section to section instead of repeating forever.
 * A null [Vibe.lickRotation] leaves the single [Vibe.lick] playing, unchanged.
 *
 * Rotation is NORMAL, always-on behavior driven at section boundaries, so the vibe needs an
 * arrangement; without one, the pool falls back to a single load-time pick. The rare "swap in an
 * original riff" event that used to live here is now a [org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly] in [Vibe.anomalies].
 */
@Serializable
data class LickRotation(
    val pool: List<Lick>,
    /**
     * Ghost notes the Complexity knob grows on a lick track survive the figure swap at a
     * section change, and take their pitch from the figure. False keeps today's wipe-on-swap.
     */
    val carryGrowth: Boolean = false,
) {
    init {
        require(pool.isNotEmpty()) { "LickRotation.pool must not be empty" }
        require(pool.size <= MAX_LICK_POOL) {
            "LickRotation.pool (${pool.size}) exceeds MAX_LICK_POOL=$MAX_LICK_POOL"
        }
    }

    companion object {
        /**
         * Max bank slots. Bounds `pool` PLUS any [org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly] lick sharing the C++ lick bank
         * (validated together in [Vibe.init]). MUST equal C++ kMaxLickPool.
         */
        const val MAX_LICK_POOL = 8
    }
}

/**
 * Which authored pattern channel a lick track renders.
 * [LEAD] plays [Vibe.lick] (rotation/anomaly capable). [BASS] plays [Vibe.bassLine],
 * the bass-owned channel with its own mutation and octave.
 */
@Serializable
enum class LickSource { LEAD, BASS }
