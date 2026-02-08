// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/naive_speech_synth.h + .cc

package org.balch.orpheus.plugins.plaits.speech

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsSpeechData

/**
 * Naive speech synth using pulse oscillator + 5 parallel bandpass SVFs.
 * Bilinearly interpolates formant parameters across phoneme and register dimensions.
 */
class NaiveSpeechSynth {
    // Impulse train oscillator state
    private var phase = 0.5f
    private var nextSample = 0.0f
    private var lpState = 1.0f
    private var hpState = 0.0f
    private var frequency = 0.0f

    private var clickDuration = 0

    // 5 formant bandpass filters + 1 pulse coloration filter
    private val filters = Array(PlaitsSpeechData.NAIVE_NUM_FORMANTS) { SynthDsp.StateVariableFilter() }
    private val pulseColoration = SynthDsp.StateVariableFilter()

    fun init() {
        phase = 0.5f
        nextSample = 0.0f
        lpState = 1.0f
        hpState = 0.0f
        frequency = 0.0f
        clickDuration = 0

        for (f in filters) f.init()
        pulseColoration.init()
        pulseColoration.setFqAccurate(800.0f / SAMPLE_RATE, 0.5f)
    }

    fun render(
        click: Boolean,
        frequency: Float,
        phoneme: Float,
        vocalRegister: Float,
        temp: FloatArray,
        excitation: FloatArray,
        output: FloatArray,
        size: Int
    ) {
        if (click) {
            clickDuration = (SAMPLE_RATE * 0.05f).toInt()
        }
        clickDuration -= minOf(clickDuration, size)

        var freq = frequency.coerceIn(0.000001f, 0.25f)
        if (clickDuration > 0) {
            freq *= 0.5f
        }

        // Generate excitation signal (impulse train via differentiated ramp + BLEP)
        renderImpulseTrain(freq, excitation, size)

        // Pulse coloration bandpass
        for (i in 0 until size) {
            excitation[i] = pulseColoration.processBp(excitation[i]) * 4.0f
        }

        val p = phoneme * (PlaitsSpeechData.NAIVE_NUM_PHONEMES - 1.001f)
        val r = vocalRegister * (PlaitsSpeechData.NAIVE_NUM_REGISTERS - 1.001f)

        val pIntegral = p.toInt()
        val pFractional = p - pIntegral
        val rIntegral = r.toInt()
        val rFractional = r - rIntegral

        // Clear output
        for (i in 0 until size) output[i] = 0.0f

        // 5 parallel formant filters
        val phonemes = PlaitsSpeechData.NAIVE_PHONEMES
        for (i in 0 until PlaitsSpeechData.NAIVE_NUM_FORMANTS) {
            val p0r0 = phonemes[pIntegral][rIntegral].formants[i]
            val p0r1 = phonemes[pIntegral][rIntegral + 1].formants[i]
            val p1r0 = phonemes[pIntegral + 1][rIntegral].formants[i]
            val p1r1 = phonemes[pIntegral + 1][rIntegral + 1].formants[i]

            val p0rF = p0r0.frequency + (p0r1.frequency - p0r0.frequency) * rFractional
            val p1rF = p1r0.frequency + (p1r1.frequency - p1r0.frequency) * rFractional
            var f = p0rF + (p1rF - p0rF) * pFractional

            val p0rA = p0r0.amplitude + (p0r1.amplitude - p0r0.amplitude) * rFractional
            val p1rA = p1r0.amplitude + (p1r1.amplitude - p1r0.amplitude) * rFractional
            val a = (p0rA + (p1rA - p0rA) * pFractional) / 256.0f

            if (f >= 160.0f) f = 160.0f
            var fNorm = A0 * PlaitsDsp.semitonesToRatio(f - 33.0f)
            if (clickDuration > 0 && i == 0) {
                fNorm *= 0.5f
            }
            filters[i].setFqAccurate(fNorm, 20.0f)

            // ProcessAdd<BAND_PASS>: process each sample, add BP*amplitude to output
            for (s in 0 until size) {
                output[s] += filters[i].processBp(excitation[s]) * a
            }
        }
    }

    /**
     * Render an impulse train using a BLEP-antialiased ramp + differentiation.
     * Ported from Oscillator::Render<OSCILLATOR_SHAPE_IMPULSE_TRAIN>.
     */
    private fun renderImpulseTrain(targetFrequency: Float, out: FloatArray, size: Int) {
        val fm = PlaitsDsp.ParameterInterpolator(frequency, targetFrequency, size)
        frequency = targetFrequency
        var ns = nextSample

        for (i in 0 until size) {
            var thisSample = ns
            ns = 0.0f

            val f = fm.next()
            phase += f

            if (phase >= 1.0f) {
                phase -= 1.0f
                val t = phase / f
                thisSample -= PlaitsDsp.thisBlepSample(t)
                ns -= PlaitsDsp.nextBlepSample(t)
            }
            ns += phase

            // Differentiate to get impulse train
            lpState += 0.25f * ((hpState - thisSample) - lpState)
            out[i] = 4.0f * lpState
            hpState = thisSample
        }
        nextSample = ns
    }

    companion object {
        private const val SAMPLE_RATE = SynthDsp.SAMPLE_RATE
        private const val A0 = (440.0f / 8.0f) / SAMPLE_RATE
    }
}
