package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Pitch/scale modifications applied during high-tension moments.
 * @param octaveShift If true, melodic tracks may shift up an octave at peak tension.
 * @param keyShift Semitones to shift the root at peak tension. 0 = none, 5 = up a 4th.
 * @param halfLick If true, lick plays at double speed during high tension (half-time feel).
 * @param chromaticPassing Probability of inserting chromatic passing tones at high tension, 0-1.
 */
@Serializable
data class TonalTension(
    val octaveShift: Boolean = false,
    val keyShift: Int = 0,
    val halfLick: Boolean = false,
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
 * @param timing How much tension affects rhythmic tightness. 0 = none, 0.2 = subtle drift.
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
