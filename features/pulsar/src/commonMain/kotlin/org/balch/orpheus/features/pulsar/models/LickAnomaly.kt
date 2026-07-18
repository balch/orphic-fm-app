package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A rare one-statement swap-in of [lick] (e.g. an original riff) over whatever lick is otherwise
 * playing. An [Anomaly] subtype: list it in [Vibe.anomalies] to enable it. On each ~2-bar
 * statement the engine may swap in [lick] with probability [chance], then reverts. Also
 * force-fired one-shot by the manual anomaly trigger.
 *
 * Requires the vibe to have a lick source — either its own [Vibe.lick] or a [Vibe.lickRotation]
 * pool (enforced by [Vibe.init]). The anomaly lick rides the SAME C++ lick bank as the rotation
 * pool: it occupies the slot past the pool and is selected by index, so pool + anomaly must fit
 * [LickRotation.MAX_LICK_POOL].
 */
@Serializable
@SerialName("lick")
data class LickAnomaly(
    val lick: Lick,
    val chance: Float = 0.02f,   // per-~2-bar-statement swap probability (rare ship rate)
) : Anomaly {
    init {
        require(chance in 0f..1f) { "LickAnomaly.chance must be in 0..1, got $chance" }
    }
}
