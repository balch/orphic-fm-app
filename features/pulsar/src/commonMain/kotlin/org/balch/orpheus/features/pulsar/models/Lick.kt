package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * One note in a bass lick pattern.
 * @param scaleDegree Index into the current scale (0 = root, 1 = 2nd degree, etc.)
 * @param duration Note length in beats (0.25 = 16th, 0.5 = 8th, 1.0 = quarter)
 * @param velocity Hit strength 0-1 (lower = ghost note feel)
 * @param glideRate Optional per-note portamento. `-1f` (default) = use the active
 *   voice's own [OrpheusEngine.glideRate]. A value in `0f..1f` overrides for this step only:
 *   0 = instant pitch jump; 0.3 = smooth; 0.6+ = very slow slide. The sentinel
 *   matches the C++ engine's `glide_rate` convention exactly (no boxing, no
 *   marshalling translation).
 */
@Serializable
data class LickStep(
    val scaleDegree: Int,
    val duration: Float,
    val velocity: Float = 0.8f,
    val glideRate: Float = -1f,
)

/**
 * A repeating melodic figure (bass riff). Assign to a [Vibe] and set
 * `lickMode = LickMode.Fill` (or `Squash`) on the track that should play it.
 * @param steps The note sequence. Max 32 steps.
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
        require(steps.all { it.glideRate == -1f || it.glideRate in 0f..1f }) {
            "LickStep.glideRate must be -1 (use track default) or in 0..1, got " +
                steps.map { it.glideRate }
        }
    }

    companion object {
        const val MAX_LICK_STEPS = 32
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

    /** Compress lick to fit within one bar. In 32-step mode, the second
     *  half is handled by the bar strategy. */
    @Serializable
    data object Squash : LickMode()

    /** Lick spans the full step count as a single continuous phrase.
     *  Bypasses the bar 1/bar 2 split entirely. */
    @Serializable
    data object Fill : LickMode()
}
