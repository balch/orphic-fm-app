// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/variable_saw_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

private const val MAX_FREQUENCY = 0.25f
private const val VARIABLE_SAW_NOTCH_DEPTH = 0.2f

/**
 * Saw with variable slope or notch, morphing to triangle.
 */
class VariableSawOscillator {
    private var phase = 0f
    private var nextSample = 0f
    private var previousPw = 0.5f
    private var high = false

    private var frequency = 0.01f
    private var pw = 0.5f
    private var waveshape = 0f

    fun init() {
        phase = 0f
        nextSample = 0f
        previousPw = 0.5f
        high = false
        frequency = 0.01f
        pw = 0.5f
        waveshape = 0f
    }

    fun render(frequency: Float, pw: Float, waveshape: Float, out: FloatArray, outOffset: Int, size: Int) {
        var f = frequency.coerceAtMost(MAX_FREQUENCY)
        var targetPw = if (f >= 0.25f) 0.5f
        else pw.coerceIn(f * 2f, 1f - 2f * f)

        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, f, size)
        val pwm = PlaitsDsp.ParameterInterpolator(this.pw, targetPw, size)
        val waveshapeMod = PlaitsDsp.ParameterInterpolator(this.waveshape, waveshape, size)

        this.frequency = f
        this.pw = targetPw
        this.waveshape = waveshape

        var nextSample = this.nextSample

        for (i in 0 until size) {
            var thisSample = nextSample
            nextSample = 0f

            val curFreq = fm.next()
            val curPw = pwm.next()
            val curWaveshape = waveshapeMod.next()
            val triangleAmount = curWaveshape
            val notchAmount = 1f - curWaveshape
            val slopeUp = 1f / curPw
            val slopeDown = 1f / (1f - curPw)

            phase += curFreq

            if (!high && phase >= curPw) {
                val triangleStep = (slopeUp + slopeDown) * curFreq * triangleAmount
                val notch = (VARIABLE_SAW_NOTCH_DEPTH + 1f - curPw) * notchAmount
                val t = (phase - curPw) / (previousPw - curPw + curFreq)
                thisSample += notch * PlaitsDsp.thisBlepSample(t)
                nextSample += notch * PlaitsDsp.nextBlepSample(t)
                thisSample -= triangleStep * PlaitsDsp.thisIntegratedBlepSample(t)
                nextSample -= triangleStep * PlaitsDsp.nextIntegratedBlepSample(t)
                high = true
            } else if (phase >= 1f) {
                phase -= 1f
                val triangleStep = (slopeUp + slopeDown) * curFreq * triangleAmount
                val notch = (VARIABLE_SAW_NOTCH_DEPTH + 1f) * notchAmount
                val t = phase / curFreq
                thisSample -= notch * PlaitsDsp.thisBlepSample(t)
                nextSample -= notch * PlaitsDsp.nextBlepSample(t)
                thisSample += triangleStep * PlaitsDsp.thisIntegratedBlepSample(t)
                nextSample += triangleStep * PlaitsDsp.nextIntegratedBlepSample(t)
                high = false
            }

            nextSample += computeNaiveSample(phase, curPw, slopeUp, slopeDown, triangleAmount, notchAmount)
            previousPw = curPw

            out[outOffset + i] = (2f * thisSample - 1f) / (1f + VARIABLE_SAW_NOTCH_DEPTH)
        }

        this.nextSample = nextSample
    }

    private fun computeNaiveSample(
        phase: Float, pw: Float, slopeUp: Float, slopeDown: Float,
        triangleAmount: Float, notchAmount: Float
    ): Float {
        val notchSaw = if (phase < pw) phase else 1f + VARIABLE_SAW_NOTCH_DEPTH
        val triangle = if (phase < pw) phase * slopeUp else 1f - (phase - pw) * slopeDown
        return notchSaw * notchAmount + triangle * triangleAmount
    }
}
