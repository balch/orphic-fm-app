package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp
import kotlin.math.abs

/**
 * PolyBLEP slope/triangle oscillator (OSCILLATOR_SHAPE_SLOPE).
 * Ported from plaits/dsp/oscillator/oscillator.h, SLOPE shape only.
 */
class SlopeOscillator {
    private var phase: Float = 0.5f
    private var nextSample: Float = 0f
    private var high: Boolean = true
    private var frequency: Float = 0.001f
    private var pw: Float = 0.5f

    fun init() {
        phase = 0.5f
        nextSample = 0f
        high = true
        frequency = 0.001f
        pw = 0.5f
    }

    fun render(frequency: Float, pw: Float, out: FloatArray, offset: Int, size: Int) {
        val clampedFreq = frequency.coerceIn(MIN_FREQUENCY, MAX_FREQUENCY)
        val clampedPw = pw.coerceIn(abs(clampedFreq) * 2f, 1f - 2f * abs(clampedFreq))

        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, clampedFreq, size)
        val pwm = PlaitsDsp.ParameterInterpolator(this.pw, clampedPw, size)
        this.frequency = clampedFreq
        this.pw = clampedPw

        var nextSample = this.nextSample

        for (i in 0 until size) {
            var thisSample = nextSample
            nextSample = 0f

            val freq = fm.next()
            val currentPw = pwm.next()
            val slopeUp = 1.0f / currentPw
            val slopeDown = 1.0f / (1.0f - currentPw)

            phase += freq

            if (high xor (phase < currentPw)) {
                val t = (phase - currentPw) / freq
                val discontinuity = (slopeUp + slopeDown) * freq
                thisSample -= PlaitsDsp.thisIntegratedBlepSample(t) * discontinuity
                nextSample -= PlaitsDsp.nextIntegratedBlepSample(t) * discontinuity
                high = phase < currentPw
            }
            if (phase >= 1.0f) {
                phase -= 1.0f
                val t = phase / freq
                val discontinuity = (slopeUp + slopeDown) * freq
                thisSample += PlaitsDsp.thisIntegratedBlepSample(t) * discontinuity
                nextSample += PlaitsDsp.nextIntegratedBlepSample(t) * discontinuity
                high = true
            }
            nextSample += if (high) {
                phase * slopeUp
            } else {
                1.0f - (phase - currentPw) * slopeDown
            }
            out[offset + i] = 2.0f * thisSample - 1.0f
        }
        this.nextSample = nextSample
    }

    companion object {
        private const val MAX_FREQUENCY = 0.25f
        private const val MIN_FREQUENCY = 0.000001f
    }
}
