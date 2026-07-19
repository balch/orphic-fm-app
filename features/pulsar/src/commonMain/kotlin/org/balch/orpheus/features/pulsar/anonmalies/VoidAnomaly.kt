package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Void Anomaly — a rare, dramatic "everything sinks toward
 * silence and swells back" moment. An [Anomaly] subtype: list it in
 * [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at [probability]; also
 * force-fired all-at-once by the manual anomaly trigger on any vibe that
 * declares it (the void only arms while a section graph is active, which
 * also gates the live trigger).
 *
 * Durations are in musical bars (16 steps). See the Anomaly Engine design spec.
 */
@Serializable
@SerialName("void")
data class VoidAnomaly(
    val probability: Float = 0.04f,     // auto-roll chance per section entry (ship value)
    val floorLevel: Float = 0.05f,      // gain multiplier at the bottom of the dip
    val rampDownBars: Float = 1.0f,
    val floorBarsMin: Float = 1.0f,
    val floorBarsMax: Float = 2.0f,
    val rampUpBars: Float = 1.5f,
    val ghostIntensity: Float = 0.5f,   // 0 = no ghost bar; >0 = one bar heard at this gain
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(floorLevel in 0f..1f) { "floorLevel must be 0..1, got $floorLevel" }
        require(floorBarsMin >= 0f && floorBarsMax >= floorBarsMin) {
            "floorBars range invalid: $floorBarsMin..$floorBarsMax"
        }
        require(ghostIntensity in 0f..1f) { "ghostIntensity must be 0..1, got $ghostIntensity" }
    }
}
