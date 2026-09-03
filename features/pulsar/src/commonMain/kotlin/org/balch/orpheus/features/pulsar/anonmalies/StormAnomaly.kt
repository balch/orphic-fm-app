package org.balch.orpheus.features.pulsar.anonmalies

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for the Storm Anomaly — a rare 1-2 strike lightning burst with rolling thunder across
 * the master bus. An [Anomaly] subtype: list it in [org.balch.orpheus.features.pulsar.models.Vibe.anomalies] to enable it.
 * Auto-fires per section at [probability]; also force-fired by the manual anomaly trigger on
 * any vibe that declares it. Guarded by the storm voice's own `!strike_active()` check, same as
 * the other Master* anomalies — a transition-armed strike effect always wins over an in-flight
 * anomaly strike (see `SectionTransition.effects` / `StrikeEffect`).
 *
 * Durations are in musical bars (16 steps); the armed window is drawn from
 * [durationBarsMin]..[durationBarsMax] and holds 1-2 strikes plus the rumble tail before
 * self-decaying. Unlike the other Master* anomalies this one arms the internal storm voice (a
 * 9th Pulsar voice, not a graph unit) — see the storm-weather design spec.
 *
 * @param probability Auto-roll chance per section entry. No ship default — every vibe that
 *   declares this anomaly picks one deliberately.
 * @param intensity Strike/rumble loudness, 0-1.
 * @param distance Perceived distance, 0 (near, sharp crack) to 1 (far, cracks suppressed).
 */
@Serializable
@SerialName("storm")
data class StormAnomaly(
    val probability: Float,
    val durationBarsMin: Int = 1,
    val durationBarsMax: Int = 2,
    val intensity: Float = 0.7f,
    val distance: Float = 0.4f,
) : Anomaly {
    init {
        require(probability in 0f..1f) { "probability must be 0..1, got $probability" }
        require(durationBarsMin in 1..durationBarsMax) {
            "durationBarsMin must be in 1..durationBarsMax ($durationBarsMax), got $durationBarsMin"
        }
    }
}
