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
 * How one non-soloing track pulls back during a bandmate's solo.
 * Set on [TrackVoice.duckingProfile]; a track that declares none ducks by exactly these
 * defaults, which are the depths the engine has always applied. So `DuckingProfile()` is a
 * no-op and every field you set is a deliberate step away from that baseline.
 *
 * Does NOT apply to tracks belonging to an always-active band member: "the kit never fully
 * steps back" is a band-level rule, and a per-track profile must not undo it.
 *
 * Only three of the six fields currently reach the audio. The other three are carried all
 * the way to the render as modifiers that nothing reads, so authoring them changes nothing
 * today — they are marked below rather than hidden, so no one tunes a dial that is not
 * connected.
 *
 * @param densityReduction Density drop — how many of the track's steps are dropped while
 *   ducking. 0.2 = the baseline; 0.5 is noticeably sparser. APPLIED.
 * @param simplify If true, drop the ornament (sub-0.45-velocity) hits and keep only the
 *   backbone. APPLIED.
 * @param fillSuppression How much to suppress fills, 0-1. 0.35 = the baseline, 0.9 = almost
 *   none. APPLIED, but only on a solo handoff bar and only for percussive tracks — that is
 *   the one seam where a ducked kit is allowed to answer with a fill.
 * @param volumeReduction Volume drop while ducking, 0-1. NOT YET APPLIED: the render folds
 *   the modifier into a local velocity that it then discards, so the voice still sounds at
 *   the step's authored velocity. Wiring it would make every band vibe's ducked tracks
 *   quieter, so it needs its own change and an ear test.
 * @param ghostReduction Ghost note reduction. NOT YET APPLIED — the modifier is computed
 *   and no consumer reads it.
 * @param reverbBoost Extra reverb send while ducking. NOT YET APPLIED — same as [ghostReduction].
 */
@Serializable
data class DuckingProfile(
    val volumeReduction: Float = 0.18f,
    val densityReduction: Float = 0.2f,
    val ghostReduction: Float = 0.35f,
    val fillSuppression: Float = 0.35f,
    val simplify: Boolean = true,
    val reverbBoost: Float = 0.1f,
) {
    companion object {
        /**
         * Floats per track in the `track_ducking_$i` bank: the six fields above plus a
         * trailing declared flag, since all-zero values are a legitimate "do not duck me"
         * and cannot double as "the vibe authored nothing".
         * Mirrors `kTrackDuckingFields` in `liborpheus_dsp/src/pulsar_limits.h`.
         */
        const val WIRE_FIELDS = 7
    }
}

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
