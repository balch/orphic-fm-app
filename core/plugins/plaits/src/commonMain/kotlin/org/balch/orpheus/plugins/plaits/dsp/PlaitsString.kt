// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/physical_modelling/string.h + string.cc

package org.balch.orpheus.plugins.plaits.dsp

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsTables
import kotlin.math.abs
import kotlin.math.min

private const val DELAY_LINE_SIZE = 1024

/**
 * Comb filter / Karplus-Strong string with two non-linearity modes:
 * - CURVED_BRIDGE: amplitude-dependent pitch modulation
 * - DISPERSION: random walk allpass stretcher
 */
class PlaitsString {
    private val string = PlaitsDelayLine(DELAY_LINE_SIZE)
    private val stretch = PlaitsDelayLine(DELAY_LINE_SIZE / 4)
    private val iirDampingFilter = SynthDsp.StateVariableFilter()

    // DC blocker (y[n] = x[n] - x[n-1] + R * y[n-1])
    private var dcPrevInput = 0f
    private var dcPrevOutput = 0f
    private val dcCoeff = 1f - 20f / SynthDsp.SAMPLE_RATE

    private var delay = 100f
    private var dispersionNoise = 0f
    private var curvedBridge = 0f

    // Linear interpolation upsampler for very low pitches
    private var srcPhase = 0f
    private val outSample = FloatArray(2)

    private val random = PlaitsDsp.Random()

    fun init() {
        delay = 100f
        reset()
    }

    fun reset() {
        string.reset()
        stretch.reset()
        iirDampingFilter.init()
        dcPrevInput = 0f
        dcPrevOutput = 0f
        dispersionNoise = 0f
        curvedBridge = 0f
        outSample[0] = 0f
        outSample[1] = 0f
        srcPhase = 0f
    }

    fun process(
        f0: Float,
        nonLinearityAmount: Float,
        brightness: Float,
        damping: Float,
        input: FloatArray,
        inputOffset: Int,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        if (nonLinearityAmount <= 0f) {
            processInternal(
                false, f0, -nonLinearityAmount, brightness, damping,
                input, inputOffset, out, outOffset, size
            )
        } else {
            processInternal(
                true, f0, nonLinearityAmount, brightness, damping,
                input, inputOffset, out, outOffset, size
            )
        }
    }

    private fun processInternal(
        dispersion: Boolean,
        f0: Float,
        nonLinearityAmount: Float,
        brightness: Float,
        damping: Float,
        input: FloatArray,
        inputOffset: Int,
        out: FloatArray,
        outOffset: Int,
        size: Int
    ) {
        val targetDelay = (1f / f0).coerceIn(4f, (DELAY_LINE_SIZE - 4).toFloat())

        var srcRatio = targetDelay * f0
        if (srcRatio >= 0.9999f) {
            srcPhase = 1f
            srcRatio = 1f
        }

        var brightnessAdj = brightness
        var dampingCutoff = min(
            12f + damping * damping * 60f + brightnessAdj * 24f,
            84f
        )
        var dampingF = min(f0 * SynthDsp.semitonesToRatio(dampingCutoff), 0.499f)

        if (damping >= 0.95f) {
            val toInfinite = 20f * (damping - 0.95f)
            brightnessAdj += toInfinite * (1f - brightnessAdj)
            dampingF += toInfinite * (0.4999f - dampingF)
            dampingCutoff += toInfinite * (128f - dampingCutoff)
        }

        iirDampingFilter.setFq(dampingF, 0.5f)

        val dampingCompensation = PlaitsDsp.interpolate(
            PlaitsTables.LUT_SVF_SHIFT, dampingCutoff, 1f
        )
        val targetDelayCompensated = targetDelay * dampingCompensation

        val delayMod = PlaitsDsp.ParameterInterpolator(delay, targetDelayCompensated, size)
        delay = targetDelayCompensated

        val stretchPoint = nonLinearityAmount * (2f - nonLinearityAmount) * 0.225f
        val stretchCorrection = ((160f / SynthDsp.SAMPLE_RATE) * targetDelay).coerceIn(1f, 2.1f)

        val noiseAmountSqrt = if (nonLinearityAmount > 0.75f) 4f * (nonLinearityAmount - 0.75f) else 0f
        val noiseAmount = noiseAmountSqrt * noiseAmountSqrt * 0.1f
        val noiseFilter = 0.06f + 0.94f * brightnessAdj * brightnessAdj

        val bridgeCurving = nonLinearityAmount * nonLinearityAmount * 0.01f
        val apGain = -0.618f * nonLinearityAmount / (0.15f + abs(nonLinearityAmount))

        for (i in 0 until size) {
            srcPhase += srcRatio
            if (srcPhase > 1f) {
                srcPhase -= 1f

                var curDelay = delayMod.next()

                if (dispersion) {
                    val noise = random.getFloat() - 0.5f
                    dispersionNoise += noiseFilter * (noise - dispersionNoise)
                    curDelay *= 1f + dispersionNoise * noiseAmount
                } else {
                    curDelay *= 1f - curvedBridge * bridgeCurving
                }

                var s: Float
                if (dispersion) {
                    val apDelay = curDelay * stretchPoint
                    val mainDelay = curDelay - apDelay * (0.408f - stretchPoint * 0.308f) * stretchCorrection
                    s = if (apDelay >= 4f && mainDelay >= 4f) {
                        val mainSample = string.read(mainDelay)
                        stretch.allpass(mainSample, apDelay, apGain)
                    } else {
                        string.readHermite(curDelay)
                    }
                } else {
                    s = string.readHermite(curDelay)
                }

                if (!dispersion) {
                    val value = abs(s) - 0.025f
                    val sign = if (s > 0f) 1f else -1.5f
                    curvedBridge = (abs(value) + value) * sign
                }

                s += input[inputOffset + i]
                s = s.coerceIn(-20f, 20f)

                // DC blocker: y[n] = x[n] - x[n-1] + R * y[n-1]
                val dcOut = s - dcPrevInput + dcCoeff * dcPrevOutput
                dcPrevInput = s
                dcPrevOutput = dcOut
                s = dcOut

                s = iirDampingFilter.processLp(s)
                string.write(s)

                outSample[1] = outSample[0]
                outSample[0] = s
            }
            out[outOffset + i] += PlaitsDsp.crossfade(outSample[1], outSample[0], srcPhase)
        }
    }
}
