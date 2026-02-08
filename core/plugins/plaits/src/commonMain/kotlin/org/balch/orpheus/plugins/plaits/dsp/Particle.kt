// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/noise/particle.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import kotlin.math.min

/**
 * Random impulse train processed by a resonant filter.
 * Ported from plaits Particle.
 */
class Particle {
    private var preGain = 0.0f
    private val filter = SynthDsp.StateVariableFilter()
    private val random = PlaitsDsp.Random()

    fun init() {
        preGain = 0.0f
        filter.init()
    }

    fun render(
        sync: Boolean,
        density: Float,
        gain: Float,
        frequency: Float,
        spread: Float,
        q: Float,
        out: FloatArray,
        aux: FloatArray,
        size: Int
    ) {
        var u = random.getFloat()
        if (sync) {
            u = density
        }
        var canRandomizeFrequency = true
        for (i in 0 until size) {
            var s = 0.0f
            if (u <= density) {
                s = u * gain
                if (canRandomizeFrequency) {
                    val ru = 2.0f * random.getFloat() - 1.0f
                    val f = min(
                        PlaitsDsp.semitonesToRatio(spread * ru) * frequency,
                        0.25f
                    )
                    preGain = 0.5f / PlaitsDsp.sqrt(q * f * PlaitsDsp.sqrt(density))
                    filter.setFq(f, q)
                    canRandomizeFrequency = false
                }
            }
            aux[i] += s
            out[i] += filter.processBp(preGain * s)
        }
    }
}
