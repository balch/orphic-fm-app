package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.WahParams

/**
 * Config for the Wah Anomaly — a rare "the whole mix ducks under a sweeping
 * tempo-synced wah for a few bars" moment. An [Anomaly] subtype: list it in
 * [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per section at [probability]; also
 * force-fired by the manual anomaly trigger on any vibe that declares it (the
 * wah only arms while a section graph is active, which also gates the live
 * trigger).
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax]. See the Anomaly Engine design spec.
 */
@Serializable
@SerialName("wah")
data class WahAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 2f,
    val durationBarsMax: Float = 4f,
    val voice: WahParams = WahParams(),
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
    }
}
