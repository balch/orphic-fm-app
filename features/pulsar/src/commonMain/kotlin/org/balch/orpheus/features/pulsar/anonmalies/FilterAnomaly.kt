package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Filter Anomaly — a rare allpass sweep across the master bus. An [Anomaly]
 * subtype: list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at [probability];
 * also force-fired by the manual anomaly trigger on any vibe that declares it.
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax]. Unlike the other Master* anomalies this one arms an
 * EXISTING engine member (`MasterFilter`, already in the master chain) — no new C++ effect,
 * just a config bank that feeds its `arm(samples, sample_rate, comb_offset)`. See the Anomaly
 * Engine design spec.
 */
@Serializable
@SerialName("filter")
data class FilterAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 2f,
    val durationBarsMax: Float = 4f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
    }
}
