package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * How a melodic track responds to the chord progression at playback time.
 */
@Serializable
enum class ChordFollow {
    /** Default — transpose pattern notes by the current chord degree. */
    FOLLOW,
    /** Override each stab with the chord root (simple chord-following bass). */
    ROOT_ONLY,
    /** Ignore progression — play pattern as generated (drone/pedal). */
    FIXED,
}

/**
 * Track behavior role — determines pattern generation, pitch handling, and which
 * evolution dimensions are available. Role-specific fields live on their subclass,
 * so invalid combinations (e.g. percussive tracks with comping) are unrepresentable.
 */
@Serializable
sealed class TrackRole {
    /** Drums — fixed pitch, rhythm patterns. */
    @Serializable
    data object Percussive : TrackRole()

    /** Single notes — scale-quantized, lick-capable. */
    @Serializable
    data class Melodic(
        /** How this track responds to the chord progression. */
        val chordFollow: ChordFollow = ChordFollow.FOLLOW,
        /** How this track maps the vibe's lick to sequencer steps. */
        val lickMode: LickMode = LickMode.None,
    ) : TrackRole()

    /** Chord voicings — progression-following, comping patterns. */
    @Serializable
    data class Chordal(
        /** Rhythmic voicing configuration. */
        val comping: ChordComping = ChordComping(),
        /** How this track responds to the chord progression. */
        val chordFollow: ChordFollow = ChordFollow.FOLLOW,
    ) : TrackRole()

    /** Engine id for C++ TrackRole enum. Keep in sync with orpheus_unit_pulsar.h. */
    val engineId: Int
        get() = when (this) {
            Percussive -> 0
            is Melodic -> 1
            is Chordal -> 2
        }
}
