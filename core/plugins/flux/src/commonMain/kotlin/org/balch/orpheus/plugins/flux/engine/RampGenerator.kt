// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles ramp/ramp_generator.h.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Simple ramp generator: phase accumulator wrapping at 1.0. */
class RampGenerator {
    private var phase = 0f

    fun init() {
        phase = 0f
    }

    fun render(frequency: Float, out: FloatArray, outOffset: Int, size: Int) {
        for (i in 0 until size) {
            phase += frequency
            if (phase >= 1f) {
                phase -= 1f
            }
            out[outOffset + i] = phase
        }
    }
}
