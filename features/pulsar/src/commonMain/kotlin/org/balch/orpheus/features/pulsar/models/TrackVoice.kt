package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * One of 8 tracks in a Vibe. Each track has two interchangeable voices ([engineEdm]
 * and [engineSpace]) that crossfade based on the energy macro, plus track-level
 * mixing/behavior properties shared across both voices.
 *
 * Engine character (volume, harmonics, timbre, morph, mod LFO, holds, sends, range,
 * LPG, glide) lives on [OrpheusEngine] so each voice can be tuned independently.
 * Track-level concerns (pan, density, role, envelope, macro map, evolution, solos)
 * live here since they describe the track regardless of which voice is active.
 *
 * @param engineEdm Voice used when the vibe leans EDM (high energy).
 * @param engineSpace Voice used when the vibe leans Space (low energy).
 * @param role Track behavior role — determines pattern generation and pitch handling.
 * @param pan Stereo position: -1.0 = hard left, 0.0 = center, 1.0 = hard right.
 * @param density Probability that a step gets a note, 0-1.
 *   0.5 = half the steps fire. Rhythm tracks want 0.3-0.6, texture tracks 0.05-0.2.
 * @param envelopeProfile Envelope shape category — determines attack/decay character.
 * @param macroMap How the 4 macro knobs (energy/complexity/space/mood) affect this track.
 * @param barStrategy How the pattern evolves across bars (REPEAT, MUTATE, FILL, etc.)
 * @param evolutionWeight How much tension-driven timbre evolution affects this track.
 *   -1 = auto (1.0 for MELODIC macroMap, 0.0 for others). 0-1 for explicit control.
 * @param soloBehavior How this track behaves when chosen as soloist (null = never solos).
 * @param duckingProfile How this track ducks during another track's solo (null = defaults).
 * @param evolution Per-track evolution config for rhythmic and pitch evolution.
 */
@Serializable
data class TrackVoice(
    val engineEdm: OrpheusEngine,
    val engineSpace: OrpheusEngine,
    val role: TrackRole = TrackRole.Percussive,
    val pan: Float = 0.0f,
    val density: Float = 0.5f,
    val envelopeProfile: EnvelopeProfile = EnvelopeProfile.RHYTHM,
    val macroMap: TrackMacroMap = TrackMacroMap.RHYTHM,
    val barStrategy: BarStrategy = BarStrategy.REPEAT,
    val evolutionWeight: Float = -1f,
    val soloBehavior: SoloBehavior? = null,
    val duckingProfile: DuckingProfile? = null,
    val evolution: Evolution = Evolution(),
)

/** Convenience: [ChordComping] if this track is [TrackRole.Chordal], else null. */
val TrackVoice.chordComping: ChordComping?
    get() = (role as? TrackRole.Chordal)?.comping

/** Convenience: [LickMode] if this track is [TrackRole.Melodic], else [LickMode.None]. */
val TrackVoice.lickMode: LickMode
    get() = (role as? TrackRole.Melodic)?.lickMode ?: LickMode.None

/** Convenience: lick scale-degree offset if [TrackRole.Melodic], else 0. */
val TrackVoice.lickDegreeOffset: Int
    get() = (role as? TrackRole.Melodic)?.lickDegreeOffset ?: 0

/** Convenience: chord-follow mode — [ChordFollow.FOLLOW] for percussive tracks. */
val TrackVoice.chordFollow: ChordFollow
    get() = when (val r = role) {
        is TrackRole.Percussive -> ChordFollow.FOLLOW
        is TrackRole.Melodic -> r.chordFollow
        is TrackRole.Chordal -> r.chordFollow
    }
