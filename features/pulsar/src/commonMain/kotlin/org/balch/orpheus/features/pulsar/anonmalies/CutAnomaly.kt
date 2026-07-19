package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Cut Anomaly — a rare rhythmic hard-gate stutter on the master bus. An
 * [Anomaly] subtype: list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at
 * [probability]; also force-fired by the manual anomaly trigger on any vibe that declares it
 * (the cut only arms while a section graph is active, which also gates the live trigger).
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax]. Within the armed window, [gateRate] sets how many
 * 16th-note steps make up one gate cycle, [duty] the open (full-level) fraction of each cycle,
 * and [depth] the level the bus falls to for the rest. See the Anomaly Engine design spec.
 */
@Serializable
@SerialName("cut")
data class CutAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 1f,
    val durationBarsMax: Float = 2f,
    val gateRate: Float = 2f,
    val duty: Float = 0.5f,
    val depth: Float = 0f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
        require(gateRate > 0f) { "gateRate must be > 0, got $gateRate" }
        require(duty in 0f..1f) { "duty must be 0..1, got $duty" }
        require(depth in 0f..1f) { "depth must be 0..1, got $depth" }
    }
}
