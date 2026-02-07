// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/z_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

private const val MAX_FREQUENCY = 0.25f

/**
 * Sinewave multiplied by and synced to a carrier.
 * Discontinuity phase runs at 2× carrier frequency.
 */
class ZOscillator {
    private var carrierPhase = 0f
    private var discontinuityPhase = 0f
    private var formantPhase = 0f
    private var nextSample = 0f

    private var carrierFrequency = 0f
    private var formantFrequency = 0f
    private var carrierShape = 0f
    private var mode = 0f

    fun init() {
        carrierPhase = 0f
        discontinuityPhase = 0f
        formantPhase = 0f
        nextSample = 0f
        carrierFrequency = 0f
        formantFrequency = 0f
        carrierShape = 0f
        mode = 0f
    }

    fun render(
        carrierFrequency: Float,
        formantFrequency: Float,
        carrierShape: Float,
        mode: Float,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val cf = carrierFrequency.coerceAtMost(MAX_FREQUENCY * 0.5f)
        val ff = formantFrequency.coerceAtMost(MAX_FREQUENCY)

        val cfMod = PlaitsDsp.ParameterInterpolator(this.carrierFrequency, cf, size)
        val ffMod = PlaitsDsp.ParameterInterpolator(this.formantFrequency, ff, size)
        val shapeMod = SubsampleParameterInterpolator(this.carrierShape, carrierShape, size)
        val modeMod = SubsampleParameterInterpolator(this.mode, mode, size)

        this.carrierFrequency = cf
        this.formantFrequency = ff
        this.carrierShape = carrierShape
        this.mode = mode

        var nextSample = this.nextSample

        for (i in 0 until size) {
            var thisSample = nextSample
            nextSample = 0f

            val f0 = cfMod.next()
            val f1 = ffMod.next()

            discontinuityPhase += 2f * f0
            carrierPhase += f0
            val reset = discontinuityPhase >= 1f

            if (reset) {
                discontinuityPhase -= 1f
                val resetTime = discontinuityPhase / (2f * f0)

                val carrierPhaseBefore = if (carrierPhase >= 1f) 1f else 0.5f
                val carrierPhaseAfter = if (carrierPhase >= 1f) 0f else 0.5f

                val before = z(
                    carrierPhaseBefore, 1f,
                    formantPhase + (1f - resetTime) * f1,
                    shapeMod.subsample(1f - resetTime),
                    modeMod.subsample(1f - resetTime)
                )
                val after = z(
                    carrierPhaseAfter, 0f, 0f,
                    shapeMod.subsample(1f),
                    modeMod.subsample(1f)
                )

                val discontinuity = after - before
                thisSample += discontinuity * PlaitsDsp.thisBlepSample(resetTime)
                nextSample += discontinuity * PlaitsDsp.nextBlepSample(resetTime)
                formantPhase = resetTime * f1

                if (carrierPhase > 1f) {
                    carrierPhase = discontinuityPhase * 0.5f
                }
            } else {
                formantPhase += f1
                if (formantPhase >= 1f) formantPhase -= 1f
            }

            if (carrierPhase >= 1f) carrierPhase -= 1f

            nextSample += z(
                carrierPhase, discontinuityPhase, formantPhase,
                shapeMod.next(), modeMod.next()
            )
            out[outOffset + i] = thisSample
        }

        this.nextSample = nextSample
    }

    private fun z(c: Float, d: Float, f: Float, shape: Float, mode: Float): Float {
        var rampDown = 0.5f * (1f + SineOscillator.sine(0.5f * d + 0.25f))

        val offset: Float
        val phaseShift: Float
        when {
            mode < 0.333f -> {
                offset = 1f
                phaseShift = 0.25f + mode * 1.50f
            }
            mode < 0.666f -> {
                phaseShift = 0.7495f - (mode - 0.33f) * 0.75f
                offset = -SineOscillator.sine(phaseShift)
            }
            else -> {
                phaseShift = 0.7495f - (mode - 0.33f) * 0.75f
                offset = 0.001f
            }
        }

        val discontinuity = SineOscillator.sine(f + phaseShift)
        val contour: Float
        if (shape < 0.5f) {
            val s = shape * 2f
            if (c >= 0.5f) {
                rampDown *= s
            }
            contour = 1f + (SineOscillator.sine(c + 0.25f) - 1f) * s
        } else {
            contour = SineOscillator.sine(c + shape * 0.5f)
        }
        return (rampDown * (offset + discontinuity) - offset) * contour
    }
}
