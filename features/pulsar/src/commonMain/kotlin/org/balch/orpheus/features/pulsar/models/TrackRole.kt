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
        /**
         * Diatonic scale-degree shift applied to this track's render of the vibe's
         * lick — parallel harmony against the lead (e.g. -2 = a fourth below in a
         * 7-note scale, -3 in blues hexatonic). Sounding notes only; rests are
         * unaffected. 0 = play the lick as written.
         */
        val lickDegreeOffset: Int = 0,
        /**
         * When true, this track's rendered audio is filtered through a per-track
         * tempo-synced bandpass wah ([Vibe.lickWah]) before it accumulates into the
         * mix — a standing timbral insert, independent of the wah anomaly. Off by
         * default (zero cost, byte-identical output).
         */
        val wahLick: Boolean = false,
        /**
         * Which authored channel this track renders when [lickMode] is Squash or Fill.
         * [LickSource.LEAD] (default) plays the vibe's lick; [LickSource.BASS] plays
         * [Vibe.bassLine], owned and mutated independently of the lead.
         */
        val lickSource: LickSource = LickSource.LEAD,
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
