// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/sine_oscillator.h (FastSineOscillator)

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Magic-circle sine oscillator with additive rendering mode.
 * Uses x/y rotation with stability correction instead of LUT lookup.
 * Ported from plaits FastSineOscillator.
 */
class FastSineOscillator {
    private var x = 1.0f
    private var y = 0.0f
    private var epsilon = 0.0f
    private var amplitude = 0.0f

    fun init() {
        x = 1.0f
        y = 0.0f
        epsilon = 0.0f
        amplitude = 0.0f
    }

    /** Render sine wave, overwriting output buffer. */
    fun render(frequency: Float, out: FloatArray, size: Int) {
        renderInternal(Mode.NORMAL, frequency, 1.0f, out, null, size)
    }

    /** Render sine wave additively into output buffer with amplitude scaling. */
    fun render(frequency: Float, amplitude: Float, out: FloatArray, size: Int) {
        renderInternal(Mode.ADDITIVE, frequency, amplitude, out, null, size)
    }

    private enum class Mode { NORMAL, ADDITIVE }

    private fun renderInternal(
        mode: Mode,
        frequency: Float,
        amplitude: Float,
        out: FloatArray,
        @Suppress("UNUSED_PARAMETER") out2: FloatArray?,
        size: Int
    ) {
        var freq = frequency
        var amp = amplitude
        if (freq >= 0.25f) {
            freq = 0.25f
            amp = 0.0f
        } else {
            amp *= 1.0f - freq * 4.0f
        }

        val epsilonInterp = PlaitsDsp.ParameterInterpolator(this.epsilon, fast2Sin(freq), size)
        val ampInterp = PlaitsDsp.ParameterInterpolator(this.amplitude, amp, size)
        this.epsilon = fast2Sin(freq)
        this.amplitude = amp

        var lx = x
        var ly = y

        // Stability correction
        val norm = lx * lx + ly * ly
        if (norm <= 0.5f || norm >= 2.0f) {
            val scale = 1f / sqrt(norm)
            lx *= scale
            ly *= scale
        }

        for (i in 0 until size) {
            val e = epsilonInterp.next()
            lx += e * ly
            ly -= e * lx
            when (mode) {
                Mode.ADDITIVE -> out[i] += ampInterp.next() * lx
                Mode.NORMAL -> out[i] = lx
            }
        }
        x = lx
        y = ly
    }

    companion object {
        /**
         * Polynomial approximation of 2*sin(pi*f).
         * Ported from plaits FastSineOscillator::Fast2Sin.
         */
        fun fast2Sin(f: Float): Float {
            val fPi = f * PI.toFloat()
            return fPi * (2.0f - (2.0f * 0.96f / 6.0f) * fPi * fPi)
        }
    }
}
