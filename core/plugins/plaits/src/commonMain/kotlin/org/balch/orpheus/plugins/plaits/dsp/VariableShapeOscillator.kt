// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/variable_shape_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

private const val MAX_FREQUENCY = 0.25f

/**
 * Continuously variable waveform: triangle → saw → square.
 * Both square and triangle have variable slope/pulse-width.
 * Phase resets can be locked to a master frequency (sync).
 */
class VariableShapeOscillator {
    private var masterPhase = 0f
    private var slavePhase = 0f
    private var nextSample = 0f
    private var previousPw = 0.5f
    private var high = false

    private var masterFrequency = 0f
    private var slaveFrequency = 0.01f
    private var pw = 0.5f
    private var waveshape = 0f

    fun init() {
        masterPhase = 0f
        slavePhase = 0f
        nextSample = 0f
        previousPw = 0.5f
        high = false
        masterFrequency = 0f
        slaveFrequency = 0.01f
        pw = 0.5f
        waveshape = 0f
    }

    fun setMasterPhase(phase: Float) {
        masterPhase = phase
    }

    /** Render without sync. */
    fun render(frequency: Float, pw: Float, waveshape: Float, out: FloatArray, outOffset: Int, size: Int) {
        renderInternal(false, 0f, frequency, pw, waveshape, out, outOffset, size)
    }

    /** Render with sync to master. */
    fun renderSync(masterFrequency: Float, frequency: Float, pw: Float, waveshape: Float, out: FloatArray, outOffset: Int, size: Int) {
        renderInternal(true, masterFrequency, frequency, pw, waveshape, out, outOffset, size)
    }

    private fun renderInternal(
        enableSync: Boolean,
        masterFreq: Float,
        frequency: Float,
        pw: Float,
        waveshape: Float,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        var mf = masterFreq.coerceAtMost(MAX_FREQUENCY)
        var f = frequency.coerceAtMost(MAX_FREQUENCY)

        var targetPw = if (f >= 0.25f) 0.5f
        else pw.coerceIn(f * 2f, 1f - 2f * f)

        val masterFm = PlaitsDsp.ParameterInterpolator(masterFrequency, mf, size)
        val fm = PlaitsDsp.ParameterInterpolator(slaveFrequency, f, size)
        val pwm = PlaitsDsp.ParameterInterpolator(this.pw, targetPw, size)
        val waveshapeMod = PlaitsDsp.ParameterInterpolator(this.waveshape, waveshape, size)

        masterFrequency = mf
        slaveFrequency = f
        this.pw = targetPw
        this.waveshape = waveshape

        var nextSample = this.nextSample

        for (i in 0 until size) {
            var reset = false
            var transitionDuringReset = false
            var resetTime = 0f

            var thisSample = nextSample
            nextSample = 0f

            val curMasterFreq = masterFm.next()
            val curSlaveFreq = fm.next()
            val curPw = pwm.next()
            val curWaveshape = waveshapeMod.next()

            val squareAmount = ((curWaveshape - 0.5f).coerceAtLeast(0f)) * 2f
            val triangleAmount = ((1f - curWaveshape * 2f).coerceAtLeast(0f))

            val slopeUp = 1f / curPw
            val slopeDown = 1f / (1f - curPw)

            if (enableSync) {
                masterPhase += curMasterFreq
                if (masterPhase >= 1f) {
                    masterPhase -= 1f
                    resetTime = masterPhase / curMasterFreq

                    var slavePhaseAtReset = slavePhase + (1f - resetTime) * curSlaveFreq
                    reset = true
                    if (slavePhaseAtReset >= 1f) {
                        slavePhaseAtReset -= 1f
                        transitionDuringReset = true
                    }
                    if (!high && slavePhaseAtReset >= curPw) {
                        transitionDuringReset = true
                    }
                    val value = computeNaiveSample(slavePhaseAtReset, curPw, slopeUp, slopeDown, triangleAmount, squareAmount)
                    thisSample -= value * PlaitsDsp.thisBlepSample(resetTime)
                    nextSample -= value * PlaitsDsp.nextBlepSample(resetTime)
                }
            }

            slavePhase += curSlaveFreq
            var loopGuard = 0
            while ((transitionDuringReset || !reset) && loopGuard++ < 2) {
                if (!high) {
                    if (slavePhase < curPw) break
                    val t = (slavePhase - curPw) / (previousPw - curPw + curSlaveFreq)
                    val triangleStep = (slopeUp + slopeDown) * curSlaveFreq * triangleAmount

                    thisSample += squareAmount * PlaitsDsp.thisBlepSample(t)
                    nextSample += squareAmount * PlaitsDsp.nextBlepSample(t)
                    thisSample -= triangleStep * PlaitsDsp.thisIntegratedBlepSample(t)
                    nextSample -= triangleStep * PlaitsDsp.nextIntegratedBlepSample(t)
                    high = true
                }

                if (high) {
                    if (slavePhase < 1f) break
                    slavePhase -= 1f
                    val t = slavePhase / curSlaveFreq
                    val triangleStep = (slopeUp + slopeDown) * curSlaveFreq * triangleAmount

                    thisSample -= (1f - triangleAmount) * PlaitsDsp.thisBlepSample(t)
                    nextSample -= (1f - triangleAmount) * PlaitsDsp.nextBlepSample(t)
                    thisSample += triangleStep * PlaitsDsp.thisIntegratedBlepSample(t)
                    nextSample += triangleStep * PlaitsDsp.nextIntegratedBlepSample(t)
                    high = false
                }
            }

            if (enableSync && reset) {
                slavePhase = resetTime * curSlaveFreq
                high = false
            }

            nextSample += computeNaiveSample(slavePhase, curPw, slopeUp, slopeDown, triangleAmount, squareAmount)
            previousPw = curPw

            out[outOffset + i] = 2f * thisSample - 1f
        }

        this.nextSample = nextSample
    }

    private fun computeNaiveSample(
        phase: Float, pw: Float, slopeUp: Float, slopeDown: Float,
        triangleAmount: Float, squareAmount: Float
    ): Float {
        var saw = phase
        val square = if (phase < pw) 0f else 1f
        val triangle = if (phase < pw) phase * slopeUp else 1f - (phase - pw) * slopeDown
        saw += (square - saw) * squareAmount
        saw += (triangle - saw) * triangleAmount
        return saw
    }
}
