// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/sam_speech_synth.h + .cc

package org.balch.orpheus.plugins.plaits.speech

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsSpeechData
import org.balch.orpheus.plugins.plaits.PlaitsTables

/**
 * SAM-inspired speech synth (Software Automatic Mouth).
 * Phase accumulator with BLEP + 3 formant oscillators tracking the pulse phase.
 */
class SAMSpeechSynth {
    private var phase = 0.0f
    private var frequency = 0.0f
    private var pulseNextSample = 0.0f
    private var pulseLp = 0.0f

    private val formantPhase = IntArray(3)
    private var consonantSamples = 0
    private var consonantIndex = 0.0f

    fun init() {
        phase = 0.0f
        frequency = 0.0f
        pulseNextSample = 0.0f
        pulseLp = 0.0f
        formantPhase.fill(0)
        consonantSamples = 0
        consonantIndex = 0.0f
    }

    fun render(
        consonant: Boolean,
        frequency: Float,
        vowel: Float,
        formantShift: Float,
        excitation: FloatArray,
        output: FloatArray,
        size: Int
    ) {
        val clampedFreq = frequency.coerceAtMost(0.0625f)

        if (consonant) {
            consonantSamples = (SAMPLE_RATE * 0.05f).toInt()
            val r = ((vowel + 3.0f * frequency + 7.0f * formantShift) * 8.0f).toInt()
            consonantIndex = (r % PlaitsSpeechData.SAM_NUM_CONSONANTS).toFloat()
        }
        consonantSamples -= minOf(consonantSamples, size)

        val phoneme = if (consonantSamples > 0) {
            consonantIndex + PlaitsSpeechData.SAM_NUM_VOWELS
        } else {
            vowel * (PlaitsSpeechData.SAM_NUM_VOWELS - 1.0001f)
        }

        // Interpolate phoneme data
        val formantFrequency = IntArray(PlaitsSpeechData.SAM_NUM_FORMANTS)
        val formantAmplitude = FloatArray(PlaitsSpeechData.SAM_NUM_FORMANTS)
        interpolatePhonemeData(phoneme, formantShift, formantFrequency, formantAmplitude)

        val fm = PlaitsDsp.ParameterInterpolator(this.frequency, clampedFreq, size)
        this.frequency = clampedFreq

        var pulseNs = pulseNextSample

        for (i in 0 until size) {
            var pulseTs = pulseNs
            pulseNs = 0.0f
            val f = fm.next()
            phase += f

            if (phase >= 1.0f) {
                phase -= 1.0f
                val t = phase / f
                // Reset formant phases on pulse
                formantPhase[0] = (t * formantFrequency[0].toFloat()).toInt()
                formantPhase[1] = (t * formantFrequency[1].toFloat()).toInt()
                formantPhase[2] = (t * formantFrequency[2].toFloat()).toInt()
                pulseTs -= PlaitsDsp.thisBlepSample(t)
                pulseNs -= PlaitsDsp.nextBlepSample(t)
            } else {
                formantPhase[0] += formantFrequency[0]
                formantPhase[1] += formantFrequency[1]
                formantPhase[2] += formantFrequency[2]
            }
            pulseNs += phase

            val d = pulseTs - 0.5f - pulseLp
            pulseLp += minOf(16.0f * f, 1.0f) * d
            excitation[i] = d

            // Formant oscillators — SineRaw uses raw uint32 phase lookup
            var s = 0.0f
            s += sineRaw(formantPhase[0]) * formantAmplitude[0]
            s += sineRaw(formantPhase[1]) * formantAmplitude[1]
            s += sineRaw(formantPhase[2]) * formantAmplitude[2]
            s *= (1.0f - phase)
            output[i] = s
        }
        pulseNextSample = pulseNs
    }

    private fun interpolatePhonemeData(
        phoneme: Float,
        formantShift: Float,
        formantFrequency: IntArray,
        formantAmplitude: FloatArray
    ) {
        val integral = phoneme.toInt()
        val fractional = phoneme - integral

        val p1 = PlaitsSpeechData.SAM_PHONEMES[integral]
        val p2 = PlaitsSpeechData.SAM_PHONEMES[integral + 1]

        val shift = 1.0f + formantShift * 2.5f
        for (i in 0 until PlaitsSpeechData.SAM_NUM_FORMANTS) {
            val f1 = p1.formants[i].frequency.toFloat()
            val f2 = p2.formants[i].frequency.toFloat()
            val f = f1 + (f2 - f1) * fractional
            formantFrequency[i] = (f * 8.0f * shift * MAX_UINT32 / SAMPLE_RATE).toInt()

            val a1 = PlaitsSpeechData.SAM_FORMANT_AMPLITUDE_LUT[p1.formants[i].amplitude]
            val a2 = PlaitsSpeechData.SAM_FORMANT_AMPLITUDE_LUT[p2.formants[i].amplitude]
            formantAmplitude[i] = a1 + (a2 - a1) * fractional
        }
    }

    /**
     * SineRaw: lookup sine from uint32 phase (no interpolation, just table index).
     * Ported from plaits SineRaw(uint32_t phase).
     */
    private fun sineRaw(phase: Int): Float {
        val index = phase ushr (32 - SINE_LUT_BITS)
        return PlaitsTables.SINE[index]
    }

    companion object {
        private const val SAMPLE_RATE = SynthDsp.SAMPLE_RATE
        private const val MAX_UINT32 = 4294967296.0f
        private const val SINE_LUT_BITS = 9
    }
}
