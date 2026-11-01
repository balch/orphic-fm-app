package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.FogPattern

/**
 * Config for the Fog Horn Anomaly: a rare fog horn phrase out of the murk. An [Anomaly] subtype;
 * list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires
 * per section at [probability]; also force-fired by the manual anomaly trigger on any vibe that
 * declares it. A phrase still sounding is never restarted.
 *
 * Like the storm it arms no master effect: it sounds the pulsar's own fog voice (an 11th voice),
 * the same one a section's `SectionFog` drives. The horn is tuned to the vibe's root, an octave
 * below C3, plus [pitch].
 *
 * @param probability Auto-roll chance per section entry. No ship default: every vibe that
 *   declares this anomaly picks one deliberately.
 * @param intensity Horn loudness, 0-1.
 * @param distance 0 (close, bright) to 1 (far, dark and quiet).
 * @param pattern The phrase to sound.
 * @param pitch Semitones from the root, -12..12.
 */
@Serializable
@SerialName("fogHorn")
data class FogHornAnomaly(
    val probability: Float,
    val intensity: Float = 0.7f,
    val distance: Float = 0.5f,
    val pattern: FogPattern = FogPattern.DUM_DUUUM,
    val pitch: Float = 0f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(intensity in 0f..1f) { "intensity must be 0..1, got $intensity" }
        require(distance in 0f..1f) { "distance must be 0..1, got $distance" }
        require(pitch in -12f..12f) { "pitch must be -12..12, got $pitch" }
    }
}
