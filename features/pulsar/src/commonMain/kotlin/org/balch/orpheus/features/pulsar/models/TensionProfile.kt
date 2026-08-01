package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * How a FILL lick loops while a tension profile truncates it, and what happens to the
 * riff's phase when that truncation is released.
 *
 * The ordinals are the wire values sent to the C++ engine (tension slot 7, mirrored by
 * `HalfLickMode` in `orpheus_unit_pulsar.h`). Do not reorder.
 */
@Serializable
enum class HalfLick {
    /** No truncation. The lick plays its full length. */
    OFF,

    /**
     * Loop only the lick's first bar so the opening figure repeats and "jams" while
     * mutation/evolution churn its tone. On release the riff re-locks to bar 1, staying
     * aligned with the chord grid.
     */
    JAM,

    /**
     * [JAM], but on release the riff deliberately spills into bar 2 and stays one bar
     * out of phase with the harmony until the next section boundary re-locks it. The
     * section states its answer phrase first, which reads as a turned-around riff.
     *
     * This is the *intentional* form of a bug that used to happen to every [JAM]
     * section: the section flip runs during track 0's boundary, so a truncated lead
     * reached its final increment with the loop length already restored and never
     * wrapped. See `test_half_lick_release_keeps_riff_phase`.
     */
    JAM_INVERTED,

    /**
     * Loop the lick's LAST bar rather than its first, so the section jams the riff's
     * answer phrase instead of its hook. On release the riff re-locks to bar 1, which
     * makes this read as a turnaround into whatever follows.
     *
     * Unlike [JAM] and [JAM_INVERTED] this shifts the loop window rather than
     * shortening it, so it has no effect on a lick that is already one bar.
     */
    JAM_LAST_BAR,
}

/**
 * Pitch/scale modifications applied during high-tension moments.
 * @param octaveShift If true, melodic tracks may shift up an octave at peak tension.
 * @param keyShift Semitones to shift the root at peak tension. 0 = none, 5 = up a 4th.
 * @param halfLick Whether a FILL lick loops only its first bar (the first 16 of the 32
 *   steps), and how its phase resolves on release. Best set per-section via
 *   [Section.tensionOverride] to jam in one section (e.g. a build) and play the full
 *   lick elsewhere. No effect on SQUASH licks (already 1 bar).
 * @param chromaticPassing Probability of inserting chromatic passing tones at high tension, 0-1.
 */
@Serializable
data class TonalTension(
    val octaveShift: Boolean = false,
    val keyShift: Int = 0,
    val halfLick: HalfLick = HalfLick.OFF,
    val chromaticPassing: Float = 0.0f,
)

/**
 * Timbre evolution over the tension cycle. Engine knobs (harmonics, timbre, morph)
 * drift between low/high values as tension rises and falls.
 *
 * @param timbreLow Timbre value at minimum tension.
 * @param timbreHigh Timbre value at peak tension.
 * @param timbreProbability Chance that timbre evolves each cycle, 0-1.
 * @param morphLow Morph at min tension (-1 = use track default).
 * @param morphHigh Morph at peak tension (-1 = use track default).
 * @param morphProbability Chance morph evolves.
 * @param harmonicsLow Harmonics at min tension (-1 = use track default).
 * @param harmonicsHigh Harmonics at peak tension (-1 = use track default).
 * @param harmonicsProbability Chance harmonics evolves.
 * @param attackPoint Where in the tension cycle the peak occurs, 0-1. 0.5 = midpoint.
 * @param releaseSpeed How quickly tension decays after the peak, 0-1. 0.3 = slow, 0.9 = fast snap-back.
 */
@Serializable
data class EvolutionTension(
    val timbreLow: Float = 0.25f,
    val timbreHigh: Float = 0.55f,
    val timbreProbability: Float = 0.7f,
    val morphLow: Float = -1f,
    val morphHigh: Float = -1f,
    val morphProbability: Float = 0.5f,
    val harmonicsLow: Float = -1f,
    val harmonicsHigh: Float = -1f,
    val harmonicsProbability: Float = 0.3f,
    val attackPoint: Float = 0.5f,
    val releaseSpeed: Float = 0.3f,
)

/**
 * Controls musical tension — the build-and-release arc that keeps things interesting.
 * Tension cycles over [innerBars] bars, optionally nested inside a longer [outerBars] cycle.
 *
 * @param innerBars Primary tension cycle length. 4 = tight builds, 8 = longer phrases, 16 = epic arcs.
 * @param outerBars Secondary (macro) tension cycle. 0 = disabled. 16-32 = album-length arcs.
 * @param outerDepth How much the outer cycle modulates the inner. 0-1.
 * @param volume How much tension affects track volumes. 0 = none, 0.3 = subtle, 0.6+ = dramatic.
 * @param timing How much tension loosens note LENGTHS (gate durations), not onsets —
 *   the "drunk" offsets stretch/shrink how long notes ring as tension peaks, most
 *   audible on LPG/envelope-gated voices. Hit placement always stays on the swung
 *   grid. 0 = none, 0.2 = subtle.
 * @param tonal Pitch/scale tension (octave shifts, chromatic passing tones).
 * @param evolution Timbre evolution over the tension cycle (harmonics/timbre/morph drift).
 */
@Serializable
data class TensionProfile(
    val innerBars: Int = 4,
    val outerBars: Int = 0,
    val outerDepth: Float = 0.5f,
    val volume: Float = 0.3f,
    val tonal: TonalTension = TonalTension(),
    val timing: Float = 0.2f,
    val evolution: EvolutionTension = EvolutionTension(),
    val spurtChance: Float = 0.0f,  // per-bar random spurt probability (0 = tension-only)
)
