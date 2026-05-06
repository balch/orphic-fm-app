package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.OrpheusEngineId

/**
 * Markov chain config for melodic solo generation. Controls how notes are chosen
 * during solos based on interval probabilities.
 *
 * @param intervalWeights 15 floats: probability of jumping -7 to +7 semitones.
 *   Index 7 = unison (staying on the same note). Higher outer values = wider leaps.
 *   Use the built-in defaults: MELODIC_DEFAULT, RHYTHMIC_DEFAULT, EFFECT_DEFAULT, WILD_DEFAULT.
 * @param restProbability Chance of inserting a rest between notes, 0-1.
 * @param holdProbability Chance of sustaining the current note instead of moving, 0-1.
 * @param densityCurveMin Note density at the start of a solo phrase.
 * @param densityCurveMax Note density at the climax of a solo phrase.
 * @param rhythmVariation How much the rhythm varies within the solo, 0-1.
 * @param chromaticPassing Chance of inserting chromatic passing tones, 0-1.
 */
@Serializable
data class SoloMarkovConfig(
    val intervalWeights: List<Float>,
    val restProbability: Float = 0.15f,
    val holdProbability: Float = 0.2f,
    val densityCurveMin: Float = 0.4f,
    val densityCurveMax: Float = 0.8f,
    val rhythmVariation: Float = 0.3f,
    val chromaticPassing: Float = 0.1f,
) {
    init {
        require(intervalWeights.size == 15) {
            "intervalWeights must have exactly 15 elements, got ${intervalWeights.size}"
        }
    }

    companion object {
        val MELODIC_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.15f, 0.25f, 0.10f, 0.25f, 0.15f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f),
        )

        val RHYTHMIC_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.01f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f, 0.10f, 0.40f, 0.10f, 0.08f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f),
            restProbability = 0.3f,
            holdProbability = 0.1f,
            densityCurveMin = 0.6f,
            densityCurveMax = 0.9f,
        )

        val EFFECT_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f, 0.12f, 0.05f, 0.06f, 0.07f, 0.08f, 0.07f, 0.06f, 0.05f),
            restProbability = 0.25f,
            holdProbability = 0.15f,
            chromaticPassing = 0.2f,
            densityCurveMin = 0.3f,
            densityCurveMax = 0.6f,
        )

        val WILD_DEFAULT = SoloMarkovConfig(
            intervalWeights = listOf(0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.064f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f, 0.067f),
            restProbability = 0.1f,
            densityCurveMin = 0.7f,
            densityCurveMax = 1.0f,
        )
    }
}

/**
 * Controls how a soloing track gravitates toward the vibe's lick pattern.
 * @param gravity Pull toward lick notes, 0-1. 0 = free improvisation, 1 = strict lick.
 * @param phraseLengthMin Minimum bars before deviating from the lick.
 * @param phraseLengthMax Maximum bars of lick-aligned playing before free improvisation.
 * @param reentryProbability Chance of snapping back to the lick after free playing, 0-1.
 */
@Serializable
data class LickBias(
    val gravity: Float = 0.5f,
    val phraseLengthMin: Int = 2,
    val phraseLengthMax: Int = 4,
    val reentryProbability: Float = 0.4f,
)

/**
 * Overrides for a track's melodic behavior during solos.
 * @param engine Switch to a different engine during solos (null = keep current).
 * @param lick Use a different lick during solos (null = keep vibe lick).
 * @param octaveShift Shift the note range up/down N octaves during solos.
 * @param noteRangeLow Override MIDI note floor during solos.
 * @param noteRangeHigh Override MIDI note ceiling during solos.
 */
@Serializable
data class MelodicOverride(
    val engine: OrpheusEngineId? = null,
    val lick: Lick? = null,
    val octaveShift: Int = 0,
    val noteRangeLow: Int? = null,
    val noteRangeHigh: Int? = null,
)

/**
 * How a track behaves when it's the active soloist.
 * Set on [TrackVoice.soloBehavior] to make a track solo-capable.
 * Tracks without this are never chosen for solos.
 *
 * @param volumeBoost Extra volume during solo, added to track volume. 0.2 = subtle lift.
 * @param densityBoost Extra density during solo. 0.3 = moderately busier.
 * @param timbreMin/timbreMax Timbre range during solo (wider = more expressive).
 * @param morphMin/morphMax Morph range during solo.
 * @param harmonicsMin/harmonicsMax Harmonics range during solo.
 * @param evolutionIntensity How much the tension system affects this track during solos, 0-1.
 * @param fillProbability Chance of playing fills at phrase boundaries during solos, 0-1.
 * @param melodicOverride Optional engine/range overrides during solos.
 * @param markovConfig Custom Markov config for this track's solo note generation.
 * @param lickBias How much the solo gravitates toward the vibe's lick pattern.
 */
@Serializable
data class SoloBehavior(
    val volumeBoost: Float = 0.2f,
    val densityBoost: Float = 0.3f,
    val timbreMin: Float = 0.2f,
    val timbreMax: Float = 0.8f,
    val morphMin: Float = 0.1f,
    val morphMax: Float = 0.7f,
    val harmonicsMin: Float = 0.2f,
    val harmonicsMax: Float = 0.8f,
    val evolutionIntensity: Float = 1.0f,
    val fillProbability: Float = 0.6f,
    val melodicOverride: MelodicOverride? = null,
    val markovConfig: SoloMarkovConfig? = null,
    val lickBias: LickBias? = null,
)

/**
 * How non-soloing tracks pull back during a solo. Set on [TrackVoice.duckingProfile].
 * Tracks without this use sensible defaults based on their [EnvelopeProfile].
 *
 * @param volumeReduction Volume drop during ducking, 0-1. 0.3 = subtle, 0.7 = dramatic.
 * @param densityReduction Density drop. 0.4 = noticeably sparser.
 * @param ghostReduction Ghost note reduction. 0.5 = half as many ghost notes.
 * @param fillSuppression How much to suppress fills. 0.8 = almost no fills while ducking.
 * @param simplify If true, simplify patterns to basic downbeats while ducking.
 * @param reverbBoost Extra reverb send while ducking (pushes backing tracks further back in the mix).
 */
@Serializable
data class DuckingProfile(
    val volumeReduction: Float = 0.3f,
    val densityReduction: Float = 0.4f,
    val ghostReduction: Float = 0.5f,
    val fillSuppression: Float = 0.8f,
    val simplify: Boolean = true,
    val reverbBoost: Float = 0.1f,
)

/**
 * Solo mode for a section — declares what kind of solo happens.
 * The band structure comes from [Vibe.band]; the section just picks the mode.
 *
 * - [LongFill]: Brief single-member spotlight on the lick. No handoff.
 * - [LickBuilder]: Aggressive lick mutation passed between members via handoff matrix.
 * - [Jam]: Free improv with configurable lick influence from prior mutations.
 */
@Serializable
sealed class SoloMode {
    @Serializable
    data class LongFill(
        val probability: Float = 0.5f,
        val barsMin: Int = 2,
        val barsMax: Int = 4,
    ) : SoloMode()

    @Serializable
    data class LickBuilder(
        val probability: Float = 0.7f,
        val mutationRate: Float = 0.5f,
    ) : SoloMode()

    @Serializable
    data class Jam(
        val probability: Float = 0.8f,
        val lickInfluence: Float = 0.5f,
    ) : SoloMode()
}
