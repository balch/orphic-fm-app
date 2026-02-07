// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/oscillator/grainlet_oscillator.h

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.plugins.plaits.PlaitsDsp

private const val MAX_FREQUENCY = 0.25f

/**
 * Phase-distorted single-cycle sine × continuously running formant sine,
 * synced to a carrier oscillator.
 */
class GrainletOscillator {
    private var carrierPhase = 0f
    private var formantPhase = 0f
    private var nextSample = 0f

    private var carrierFrequency = 0f
    private var formantFrequency = 0f
    private var carrierShape = 0f
    private var carrierBleed = 0f

    fun init() {
        carrierPhase = 0f
        formantPhase = 0f
        nextSample = 0f
        carrierFrequency = 0f
        formantFrequency = 0f
        carrierShape = 0f
        carrierBleed = 0f
    }

    fun render(
        carrierFrequency: Float,
        formantFrequency: Float,
        carrierShape: Float,
        carrierBleed: Float,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val cf = carrierFrequency.coerceAtMost(MAX_FREQUENCY * 0.5f)
        val ff = formantFrequency.coerceAtMost(MAX_FREQUENCY)

        val cfMod = PlaitsDsp.ParameterInterpolator(this.carrierFrequency, cf, size)
        val ffMod = PlaitsDsp.ParameterInterpolator(this.formantFrequency, ff, size)
        val shapeMod = SubsampleParameterInterpolator(this.carrierShape, carrierShape, size)
        val bleedMod = SubsampleParameterInterpolator(this.carrierBleed, carrierBleed, size)

        this.carrierFrequency = cf
        this.formantFrequency = ff
        this.carrierShape = carrierShape
        this.carrierBleed = carrierBleed

        var nextSample = this.nextSample

        for (i in 0 until size) {
            var thisSample = nextSample
            nextSample = 0f

            val f0 = cfMod.next()
            val f1 = ffMod.next()

            carrierPhase += f0
            val reset = carrierPhase >= 1f

            if (reset) {
                carrierPhase -= 1f
                val resetTime = carrierPhase / f0

                val before = grainlet(
                    1f,
                    formantPhase + (1f - resetTime) * f1,
                    shapeMod.subsample(1f - resetTime),
                    bleedMod.subsample(1f - resetTime)
                )
                val after = grainlet(
                    0f, 0f,
                    shapeMod.subsample(1f),
                    bleedMod.subsample(1f)
                )

                val discontinuity = after - before
                thisSample += discontinuity * PlaitsDsp.thisBlepSample(resetTime)
                nextSample += discontinuity * PlaitsDsp.nextBlepSample(resetTime)
                formantPhase = resetTime * f1
            } else {
                formantPhase += f1
                if (formantPhase >= 1f) formantPhase -= 1f
            }

            nextSample += grainlet(
                carrierPhase, formantPhase,
                shapeMod.next(), bleedMod.next()
            )
            out[outOffset + i] = thisSample
        }

        this.nextSample = nextSample
    }

    private fun carrier(phase: Float, shape: Float): Float {
        val s = shape * 3f
        val shapeIntegral = s.toInt().coerceAtMost(2)
        val shapeFractional = s - shapeIntegral
        val t = 1f - shapeFractional

        var p = phase
        when (shapeIntegral) {
            0 -> {
                p = p * (1f + t * t * t * 15f)
                if (p >= 1f) p = 1f
                p += 0.75f
            }
            1 -> {
                val breakpoint = 0.001f + 0.499f * t * t * t
                p = if (p < breakpoint) {
                    p * (0.5f / breakpoint)
                } else {
                    0.5f + (p - breakpoint) * 0.5f / (1f - breakpoint)
                }
                p += 0.75f
            }
            else -> {
                val t2 = 1f - t
                p = 0.25f + p * (0.5f + t2 * t2 * t2 * 14.5f)
                if (p >= 0.75f) p = 0.75f
            }
        }
        return (SineOscillator.sine(p) + 1f) * 0.25f
    }

    private fun grainlet(carrierPhase: Float, formantPhase: Float, shape: Float, bleed: Float): Float {
        val c = carrier(carrierPhase, shape)
        val formant = SineOscillator.sine(formantPhase)
        return c * (formant + bleed) / (1f + bleed)
    }
}

/**
 * ParameterInterpolator with subsample access for PolyBLEP discontinuity computation.
 * The subsample method returns the interpolated value at fractional position t
 * within the current sample, without advancing the interpolator state.
 */
internal class SubsampleParameterInterpolator(
    private var value: Float,
    target: Float,
    size: Int
) {
    private val increment = (target - value) / size

    /** Return interpolated value at fractional position t within the current sample step. */
    fun subsample(t: Float): Float = value + increment * t

    fun next(): Float {
        value += increment
        return value
    }
}
