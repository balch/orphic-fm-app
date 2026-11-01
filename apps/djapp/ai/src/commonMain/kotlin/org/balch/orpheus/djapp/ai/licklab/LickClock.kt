package org.balch.orpheus.djapp.ai.licklab

import kotlin.math.floor
import kotlin.math.max

/**
 * The Lick Lab beat clock: monotonic time over the live beat length, extrapolated between music pulses and
 * nudged toward each new pulse's beat phase so drift is corrected without a jump. Never runs backwards.
 */
internal class LickClock(private val nowMs: () -> Long) {
    private var anchorMs = Long.MIN_VALUE
    private var anchorBeat = 0.0
    private var lastPulse: Any? = null
    private var locked = false
    private var out = 0.0

    /** [pulse] marks a sample by identity; [phase] is its beat phase, null when the music has no beat. */
    fun beat(msPerBeat: Double, pulse: Any?, phase: Double?): Double {
        val now = nowMs()
        // Re-anchoring on every read keeps a tempo change from rescaling time already counted.
        if (anchorMs != Long.MIN_VALUE && msPerBeat > 0.0) anchorBeat += (now - anchorMs) / msPerBeat
        anchorMs = now
        if (phase != null && pulse !== lastPulse) {
            lastPulse = pulse
            var target = floor(anchorBeat) + phase
            if (target - anchorBeat > 0.5) target -= 1.0 else if (anchorBeat - target > 0.5) target += 1.0
            anchorBeat += (target - anchorBeat) * if (locked) LOCK_GAIN else 1.0
            locked = true
        }
        out = max(out, anchorBeat)
        return out
    }

    private companion object {
        const val LOCK_GAIN = 0.25
    }
}
