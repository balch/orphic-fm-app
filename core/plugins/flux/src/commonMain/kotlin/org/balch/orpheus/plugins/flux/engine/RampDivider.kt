// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles ramp/ramp_divider.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Phase-synchronized ramp divider with configurable p/q ratio.
 *  Detects input phase wraparound, counts divisions, outputs scaled ramp. */
class RampDivider {
    private var phase = 0f
    private var trainPhase = 0f
    private var maxTrainPhase = 1f
    private var fRatio = 0.99999f
    private var resetCounter = 1
    private var resetAtNextPulse = false

    fun init() {
        phase = 0f
        trainPhase = 0f
        maxTrainPhase = 1f
        fRatio = 0.99999f
        resetCounter = 1
    }

    fun reset() {
        resetAtNextPulse = true
    }

    fun process(ratio: Ratio, input: FloatArray, output: FloatArray, inOffset: Int, outOffset: Int, size: Int) {
        for (s in 0 until size) {
            val newPhase = input[inOffset + s]
            var frequency = newPhase - phase
            if (frequency < 0f) {
                if (resetAtNextPulse) {
                    resetAtNextPulse = false
                    resetCounter = 1
                }
                frequency += 1f
                resetCounter--
                if (resetCounter == 0) {
                    trainPhase = newPhase
                    resetCounter = ratio.q
                    fRatio = ratio.toFloat() * SlaveRamp.MAX_RAMP_VALUE
                    frequency = 0f
                    maxTrainPhase = ratio.q.toFloat()
                }
            }

            trainPhase += frequency
            if (trainPhase >= maxTrainPhase) {
                trainPhase = maxTrainPhase
            }

            var outputPhase = trainPhase * fRatio
            outputPhase -= outputPhase.toInt().toFloat()
            output[outOffset + s] = outputPhase
            phase = newPhase
        }
    }
}
