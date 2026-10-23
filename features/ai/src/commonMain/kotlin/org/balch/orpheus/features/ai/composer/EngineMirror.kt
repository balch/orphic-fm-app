package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice

/**
 * Kotlin mirror of the render rules that decide whether tracks 5-7 are heard: the mid-energy
 * notch and track 7's note gate (orpheus_unit_pulsar.cpp, pulsar_mod_ranges.h). EngineMirrorParityTest
 * holds every constant to the C++.
 */
internal object EngineMirror {

    // texture_energy_curve (pulsar_mod_ranges.h): full below 0.35 and from 0.65, ramps
    // between, and NOTCH_GAIN (-26 dB) from 0.45 to 0.55.
    const val NOTCH_FULL_BELOW = 0.35f
    const val NOTCH_FLOOR_FROM = 0.45f
    const val NOTCH_FLOOR_TO = 0.55f
    const val NOTCH_FULL_FROM = 0.65f
    const val NOTCH_GAIN = 0.05f

    // compute_fx_probability (orpheus_unit_pulsar.cpp): open below energy 1 - FX_LOW_EDGE, or
    // above FX_HIGH_ENERGY with complexity above FX_HIGH_COMPLEXITY.
    const val FX_LOW_EDGE = 0.6f
    const val FX_LOW_SLOPE = 2.5f
    const val FX_HIGH_COMPLEXITY = 0.7f
    const val FX_HIGH_COMPLEXITY_SLOPE = 3.3f
    const val FX_HIGH_ENERGY = 0.6f
    const val FX_HIGH_ENERGY_SLOPE = 2.5f

    /** The FX gate's "closed" threshold on track 7 (`fx_prob <= 0.001f`). */
    const val FX_GATE_EPSILON = 0.001f

    /** A gain at or above this (about -6 dB) counts as heard. */
    const val AUDIBLE_GAIN = 0.5f

    const val FX_TRACK = 7
    const val FIRST_NOTCH_TRACK = 5

    /** One engine's harmonics, timbre and morph floors. */
    data class Floors(val harmonics: Float = 0f, val timbre: Float = 0f, val morph: Float = 0f)

    // kEngineModRanges' playability floors (pulsar_mod_ranges.h), which render applies to engines
    // 0-23 after every modulation. Unlisted engines have none.
    val PLAYABILITY_FLOORS: Map<OrpheusEngineId, Floors> = mapOf(
        OrpheusEngineId.VCF to Floors(timbre = 0.1f),
        OrpheusEngineId.PD to Floors(timbre = 0.2f),
        OrpheusEngineId.TRN to Floors(timbre = 0.15f, morph = 0.1f),
        OrpheusEngineId.VA to Floors(timbre = 0.35f),
        OrpheusEngineId.WSH to Floors(timbre = 0.2f),
        OrpheusEngineId.FM to Floors(timbre = 0.15f),
        OrpheusEngineId.STR to Floors(harmonics = 0.28f),
    )

    fun floors(id: OrpheusEngineId): Floors = PLAYABILITY_FLOORS[id] ?: Floors()

    fun textureEnergyCurve(energy: Float): Float = when {
        energy < NOTCH_FULL_BELOW -> 1f
        energy < NOTCH_FLOOR_FROM ->
            1f - (energy - NOTCH_FULL_BELOW) * ((1f - NOTCH_GAIN) / (NOTCH_FLOOR_FROM - NOTCH_FULL_BELOW))
        energy < NOTCH_FLOOR_TO -> NOTCH_GAIN
        energy < NOTCH_FULL_FROM ->
            NOTCH_GAIN + (energy - NOTCH_FLOOR_TO) * ((1f - NOTCH_GAIN) / (NOTCH_FULL_FROM - NOTCH_FLOOR_TO))
        else -> 1f
    }

    fun fxProbability(energy: Float, complexity: Float): Float {
        val low = maxOf(0f, (1f - energy) - FX_LOW_EDGE) * FX_LOW_SLOPE
        val high = maxOf(0f, (complexity - FX_HIGH_COMPLEXITY) * FX_HIGH_COMPLEXITY_SLOPE) *
            maxOf(0f, (energy - FX_HIGH_ENERGY) * FX_HIGH_ENERGY_SLOPE)
        return maxOf(low, high)
    }

    /** Melodic with textureLine: the C++ reads it only on a Melodic role. */
    private fun textureLine(t: TrackVoice): Boolean = (t.role as? TrackRole.Melodic)?.textureLine == true

    /** A Melodic track carrying the vibe's lick (any lickMode but None). */
    private fun lickLine(t: TrackVoice): Boolean = (t.role as? TrackRole.Melodic)?.lickMode.let { it != null && it != LickMode.None }

    /**
     * The level the notch leaves [track] at, 1 off tracks 5-7. A Chordal track, and a lick
     * line without textureLine, are lifted to full. No band solo is assumed.
     */
    fun notchGain(track: Int, t: TrackVoice, energy: Float): Float {
        if (track < FIRST_NOTCH_TRACK) return 1f
        val curve = textureEnergyCurve(energy)
        if (t.role is TrackRole.Percussive) return curve
        val lifted = t.role is TrackRole.Chordal || (lickLine(t) && !textureLine(t))
        return if (lifted) 1f else curve
    }

    /** Track 7 fires only while the FX probability is open or it is a Melodic textureLine. */
    fun fxGateOpen(track: Int, t: TrackVoice, energy: Float, complexity: Float): Boolean =
        track != FX_TRACK || fxProbability(energy, complexity) > FX_GATE_EPSILON || textureLine(t)

    /** True when [track] plays at a heard level at these macros. */
    fun sounds(track: Int, t: TrackVoice, energy: Float, complexity: Float): Boolean =
        fxGateOpen(track, t, energy, complexity) && notchGain(track, t, energy) >= AUDIBLE_GAIN
}
