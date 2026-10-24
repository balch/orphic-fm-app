package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * A section's fog horn: a low ship's horn that sounds a [pattern] on the section's first bar and
 * every [intervalBars] bars after, in the vibe's key. Reusable like [SectionWeather]; null = no
 * fog. The vibe's own FogHornAnomaly sounds the same voice at random.
 *
 * The horn is tuned to the vibe's root, an octave below C3, plus [pitch]. It sits in the vibe's
 * reverb and delay, so a distant horn is mostly room.
 *
 * @param level Horn loudness, 0-1. 0 = silent.
 * @param intervalBars A phrase sounds every this many bars, 1-16. A phrase still ringing is
 *   never restarted, so a short interval on a long pattern just sounds back to back.
 * @param pattern [FogPattern.SINGLE] is one long blast; [FogPattern.DUM_DUUUM] is short, long, short.
 * @param pitch Semitones from the root, -12..12.
 * @param distance 0 (close, bright) to 1 (far, dark and quiet).
 */
@Serializable
data class SectionFog(
    val level: Float = 0f,
    val intervalBars: Int = 4,
    val pattern: FogPattern = FogPattern.DUM_DUUUM,
    val pitch: Float = 0f,
    val distance: Float = 0.4f,
) {
    init {
        require(level in 0f..1f) { "SectionFog.level must be 0..1, got $level" }
        require(intervalBars in 1..16) { "SectionFog.intervalBars must be 1..16, got $intervalBars" }
        require(pitch in -12f..12f) { "SectionFog.pitch must be -12..12, got $pitch" }
        require(distance in 0f..1f) { "SectionFog.distance must be 0..1, got $distance" }
    }
}

/** Shapes of a fog horn phrase. The ordinal is the wire value (`fog::Pattern` in `pulsar_fog.h`). */
@Serializable
enum class FogPattern { SINGLE, DUM_DUUUM }
