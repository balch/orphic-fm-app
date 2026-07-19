package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Swell Anomaly — a rare "the whole mix crests and settles" moment on the
 * master bus. An [Anomaly] subtype: list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it. Auto-fires per
 * section at [probability]; also force-fired by the manual anomaly trigger on any vibe that
 * declares it (the swell only arms while a section graph is active, which also gates the
 * live trigger).
 *
 * Durations are in musical bars (16 steps); the armed length is drawn from
 * [durationBarsMin]..[durationBarsMax]. [peakLevel] may intentionally exceed 1.0 (a genuine
 * gain boost at the swell's crest) — do NOT clamp it. See the Anomaly Engine design spec.
 */
@Serializable
@SerialName("swell")
data class SwellAnomaly(
    val probability: Float = 0.03f,   // auto-roll chance per section entry (ship value)
    val durationBarsMin: Float = 2f,
    val durationBarsMax: Float = 4f,
    val startLevel: Float = 1f,
    val peakLevel: Float = 1.3f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMax >= durationBarsMin) {
            "durationBars range invalid: $durationBarsMin..$durationBarsMax"
        }
        require(startLevel >= 0f) { "startLevel must be >= 0, got $startLevel" }
        require(peakLevel >= 0f) { "peakLevel must be >= 0, got $peakLevel" }
    }
}
