// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/lpc_speech_synth.h + .cc

package org.balch.orpheus.plugins.plaits.speech

import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsSpeechData

/**
 * LPC-10 speech synthesizer.
 * 10th-order lattice filter driven by voiced (impulse) + unvoiced (noise) excitation.
 */
class LPCSpeechSynth {
    private var phase = 0.0f
    private var frequency = 0.0125f
    private var noiseEnergy = 0.0f
    private var pulseEnergy = 0.0f
    private var nextSample = 0.0f
    private var excitationPulseSampleIndex = 0

    private val k = FloatArray(PlaitsSpeechData.LPC_ORDER)
    private val s = FloatArray(PlaitsSpeechData.LPC_ORDER + 1)

    private val random = PlaitsDsp.Random()

    fun init() {
        phase = 0.0f
        frequency = 0.0125f
        noiseEnergy = 0.0f
        pulseEnergy = 0.0f
        nextSample = 0.0f
        excitationPulseSampleIndex = 0
        k.fill(0f)
        s.fill(0f)
    }

    fun render(
        prosodyAmount: Float,
        pitchShift: Float,
        excitation: FloatArray, excitationOffset: Int,
        output: FloatArray, outputOffset: Int,
        size: Int
    ) {
        val baseF0 = PlaitsSpeechData.LPC_DEFAULT_F0 / 8000.0f
        val d = frequency - baseF0
        var f = (baseF0 + d * prosodyAmount) * pitchShift
        f = f.coerceIn(0.0f, 0.5f)

        var ns = nextSample
        for (i in 0 until size) {
            phase += f

            var thisSample = ns
            ns = 0.0f

            if (phase >= 1.0f) {
                phase -= 1.0f
                val resetTime = phase / f
                val resetSample = (32.0f * resetTime).toInt()

                var discontinuity = 0.0f
                if (excitationPulseSampleIndex < PlaitsSpeechData.LPC_EXCITATION_PULSE_SIZE) {
                    excitationPulseSampleIndex -= resetSample
                    val sv = PlaitsSpeechData.LPC_EXCITATION_PULSE[excitationPulseSampleIndex]
                    discontinuity = sv.toFloat() / 128.0f * pulseEnergy
                }

                thisSample += -discontinuity * PlaitsDsp.thisBlepSample(resetTime)
                ns += -discontinuity * PlaitsDsp.nextBlepSample(resetTime)

                excitationPulseSampleIndex = resetSample
            }

            // Lattice filter excitation
            val e = FloatArray(11)
            e[10] = if (random.getWord() > 0) noiseEnergy else -noiseEnergy
            if (excitationPulseSampleIndex < PlaitsSpeechData.LPC_EXCITATION_PULSE_SIZE) {
                val sv = PlaitsSpeechData.LPC_EXCITATION_PULSE[excitationPulseSampleIndex]
                ns += sv.toFloat() / 128.0f * pulseEnergy
                excitationPulseSampleIndex += 32
            }
            e[10] += thisSample
            e[10] *= 1.5f

            // Lattice filter (unrolled 10th order)
            e[9] = e[10] - k[9] * s[9]
            e[8] = e[9] - k[8] * s[8]
            e[7] = e[8] - k[7] * s[7]
            e[6] = e[7] - k[6] * s[6]
            e[5] = e[6] - k[5] * s[5]
            e[4] = e[5] - k[4] * s[4]
            e[3] = e[4] - k[3] * s[3]
            e[2] = e[3] - k[2] * s[2]
            e[1] = e[2] - k[1] * s[1]
            e[0] = e[1] - k[0] * s[0]

            e[0] = e[0].coerceIn(-2.0f, 2.0f)

            s[9] = s[8] + k[8] * e[8]
            s[8] = s[7] + k[7] * e[7]
            s[7] = s[6] + k[6] * e[6]
            s[6] = s[5] + k[5] * e[5]
            s[5] = s[4] + k[4] * e[4]
            s[4] = s[3] + k[3] * e[3]
            s[3] = s[2] + k[2] * e[2]
            s[2] = s[1] + k[1] * e[1]
            s[1] = s[0] + k[0] * e[0]
            s[0] = e[0]

            excitation[excitationOffset + i] = e[10]
            output[outputOffset + i] = e[0]
        }
        nextSample = ns
    }

    fun playFrame(frames: List<PlaitsSpeechData.LpcFrame>, frame: Float, interpolate: Boolean) {
        val maxIndex = frames.size - 2
        if (maxIndex < 0) return
        val integral = frame.toInt().coerceIn(0, maxIndex)
        val fractional = if (interpolate) (frame - frame.toInt()).coerceIn(0f, 1f) else 0.0f
        playFrame(frames[integral], frames[integral + 1], fractional)
    }

    private fun playFrame(f1: PlaitsSpeechData.LpcFrame, f2: PlaitsSpeechData.LpcFrame, blend: Float) {
        val freq1 = if (f1.period == 0) frequency else 1.0f / f1.period.toFloat()
        val freq2 = if (f2.period == 0) frequency else 1.0f / f2.period.toFloat()
        frequency = freq1 + (freq2 - freq1) * blend

        val energy1 = f1.energy.toFloat() / 256.0f
        val energy2 = f2.energy.toFloat() / 256.0f
        val noiseEnergy1 = if (f1.period == 0) energy1 else 0.0f
        val noiseEnergy2 = if (f2.period == 0) energy2 else 0.0f
        noiseEnergy = noiseEnergy1 + (noiseEnergy2 - noiseEnergy1) * blend

        val pulseEnergy1 = if (f1.period != 0) energy1 else 0.0f
        val pulseEnergy2 = if (f2.period != 0) energy2 else 0.0f
        pulseEnergy = pulseEnergy1 + (pulseEnergy2 - pulseEnergy1) * blend

        k[0] = blendCoefficient(f1.k0, f2.k0, blend, 32768f)
        k[1] = blendCoefficient(f1.k1, f2.k1, blend, 32768f)
        k[2] = blendCoefficient(f1.k2, f2.k2, blend, 128f)
        k[3] = blendCoefficient(f1.k3, f2.k3, blend, 128f)
        k[4] = blendCoefficient(f1.k4, f2.k4, blend, 128f)
        k[5] = blendCoefficient(f1.k5, f2.k5, blend, 128f)
        k[6] = blendCoefficient(f1.k6, f2.k6, blend, 128f)
        k[7] = blendCoefficient(f1.k7, f2.k7, blend, 128f)
        k[8] = blendCoefficient(f1.k8, f2.k8, blend, 128f)
        k[9] = blendCoefficient(f1.k9, f2.k9, blend, 128f)
    }

    private fun blendCoefficient(a: Int, b: Int, blend: Float, scale: Float): Float {
        val af = a.toFloat() / scale
        val bf = b.toFloat() / scale
        return af + (bf - af) * blend
    }
}
