package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Scratch Anomaly — a rare single-shot vinyl needle-skip on the master bus. An
 * [Anomaly] subtype: list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at
 * [probability]; also force-fired by the manual anomaly trigger on any vibe that declares it
 * (the scratch only arms while a section graph is active, which also gates the live trigger).
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax]. Unlike the other Master* anomalies this one arms an
 * EXISTING engine member (`MasterScratch`, already in the master chain for the section-exit
 * record-scratch feature) — no new C++ effect, just a config bank that feeds its
 * `arm(samples, sample_rate, seed_offset)`. See the Anomaly Engine design spec.
 */
@Serializable
@SerialName("scratch")
data class ScratchAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 1f,
    val durationBarsMax: Float = 2f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
    }
}
