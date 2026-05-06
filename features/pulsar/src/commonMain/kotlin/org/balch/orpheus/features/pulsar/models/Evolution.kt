package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * How moved notes relate to their melodic content during MUTATE transforms.
 * Only meaningful for [TrackRole.Melodic] tracks with rhythmic evolution.
 */
@Serializable
enum class NoteFollowMode {
    /** Notes keep their identity, shift in time with the step. */
    SLIDE,
    /** Rhythmic positions keep their role, notes get redistributed. */
    CONTOUR,
    /** Blend — notes partially follow position, partially follow identity. */
    BLEND,
}

/**
 * Pitch/voicing evolution — role-specific.
 * Use [Contour] for MELODIC tracks (Markov pitch drift),
 * [Voicing] for CHORDAL tracks (inversion shifts, chord substitution).
 */
@Serializable
sealed class PitchEvolution {
    /** Markov contour for MELODIC tracks — second-order pitch drift. */
    @Serializable
    data class Contour(
        val driftRange: Float = 0.1f,
    ) : PitchEvolution()

    /** Voicing evolution for CHORDAL tracks — inversion shifts and chord substitution. */
    @Serializable
    data class Voicing(
        val tensionResponse: Float = 1.0f,
    ) : PitchEvolution()
}

/**
 * Rhythmic position evolution via MUTATE transforms (SHIFT/SYNCOPATE/RESHAPE).
 * Works for all [TrackRole]s.
 */
@Serializable
data class RhythmicEvolution(
    /** How much tension drives transform intensity.
     *  0 = always SHIFT, 1 = full SHIFT→SYNCOPATE→RESHAPE arc. */
    val tensionResponse: Float = 1.0f,
    /** How moved notes relate to their melodic content. Only meaningful for MELODIC tracks. */
    val noteFollow: NoteFollowMode = NoteFollowMode.SLIDE,
)

/**
 * Per-track evolution config. Composes rhythmic (position) and pitch (content)
 * evolution independently. Both can be active simultaneously.
 */
@Serializable
data class Evolution(
    /** Rhythmic position evolution via MUTATE transforms. Null = no rhythmic evolution. */
    val rhythmic: RhythmicEvolution? = null,
    /** Pitch/voicing evolution. Null = no pitch evolution. */
    val pitch: PitchEvolution? = null,
)
