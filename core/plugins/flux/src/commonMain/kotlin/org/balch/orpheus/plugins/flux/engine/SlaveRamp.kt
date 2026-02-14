// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles ramp/slave_ramp.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** A ramp that follows a master ramp through division/multiplication.
 *  Two modes:
 *  - Ratio-based: clock division patterns
 *  - Bernoulli-based: adaptive slope with probabilistic completion */
class SlaveRamp {
    private var phase = 0f
    private var maxPhase = MAX_RAMP_VALUE
    private var ratio = 1f
    private var pulseWidth = 0f
    private var target = 1f
    private var pulseLength = 0
    private var bernoulli = false
    private var mustComplete = false

    // Per-sample output holders
    var outputPhase = 0f; private set
    var outputGate = false; private set

    fun init() {
        phase = 0f
        maxPhase = MAX_RAMP_VALUE
        ratio = 1f
        pulseWidth = 0f
        target = 1f
        pulseLength = 0
        bernoulli = false
        mustComplete = false
    }

    fun reset() {
        init()
        phase = 1f
    }

    /** Initialize with a multiplied/divided rate compared to the master. */
    fun init(patternLength: Int, ratio: Ratio, pulseWidth: Float) {
        bernoulli = false
        phase = 0f
        maxPhase = patternLength.toFloat() * MAX_RAMP_VALUE
        this.ratio = ratio.toFloat()
        this.pulseWidth = pulseWidth
        target = 1f
        pulseLength = 0
    }

    /** Initialize with adaptive slope (Bernoulli mode). */
    fun init(mustComplete: Boolean, pulseWidth: Float, expectedValue: Float) {
        bernoulli = true

        if (this.mustComplete) {
            phase = 0f
            this.pulseWidth = pulseWidth
            ratio = 1f
            pulseLength = 0
        }

        ratio = if (!mustComplete) {
            (1f - phase) * expectedValue
        } else {
            1f - phase
        }
        this.mustComplete = mustComplete
    }

    /** Process one sample. Writes to [outputPhase] and [outputGate]. */
    fun process(frequency: Float) {
        val outPhase: Float
        if (bernoulli) {
            phase += frequency * ratio
            outPhase = if (phase >= 1f) 1f else phase
        } else {
            phase += frequency
            if (phase >= maxPhase) {
                phase = maxPhase
            }
            var op = phase * ratio
            if (op > target) {
                pulseLength = 0
                target += 1f
            }
            op -= op.toInt().toFloat()
            outPhase = op
        }
        outputPhase = outPhase
        outputGate = if (pulseWidth == 0f) {
            pulseLength < 32 && outPhase <= 0.5f
        } else {
            outPhase < pulseWidth
        }
        ++pulseLength
    }

    companion object {
        const val MAX_RAMP_VALUE = 0.9999f
    }
}
