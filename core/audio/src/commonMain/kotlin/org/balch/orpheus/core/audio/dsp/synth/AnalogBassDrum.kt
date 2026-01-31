package org.balch.orpheus.core.audio.dsp.synth

import kotlin.math.abs
import kotlin.math.min

/**
 * 808 Bass Drum model ported from Mutable Instruments Plaits.
 */
class AnalogBassDrum {
    private val resonator = SynthDsp.StateVariableFilter()
    private var pulseRemainingSamples = 0
    private var fmPulseRemainingSamples = 0
    private var pulse = 0f
    private var pulseHeight = 0f
    private var pulseLp = 0f
    private var fmPulseLp = 0f
    private var retrigPulse = 0f
    private var lpOut = 0f
    private var toneLp = 0f
    
    private val sampleRate = SynthDsp.SAMPLE_RATE

    fun init() {
        resonator.init()
        reset()
    }

    fun reset() {
        pulseRemainingSamples = 0
        fmPulseRemainingSamples = 0
        pulse = 0f
        pulseHeight = 0f
        pulseLp = 0f
        fmPulseLp = 0f
        retrigPulse = 0f
        lpOut = 0f
        toneLp = 0f
        resonator.reset()
    }

    private fun diode(x: Float): Float {
        return if (x >= 0f) {
            x
        } else {
            val x2 = x * 2.0f
            0.7f * x2 / (1.0f + abs(x2))
        }
    }

    /**
     * Process one sample.
     * @param trigger true on the first sample of a hit
     * @param accent 0..1
     * @param f0 normalized base frequency (Hz / SampleRate)
     * @param tone 0..1
     * @param decay 0..1
     * @param attackFm 0..1
     * @param selfFm 0..1
     */
    fun process(
        trigger: Boolean,
        accent: Float,
        f0: Float,
        tone: Float,
        decay: Float,
        attackFm: Float,
        selfFm: Float
    ): Float {
        val kTriggerPulseDuration = (1.0e-3f * sampleRate).toInt()
        val kFMPulseDuration = (6.0e-3f * sampleRate).toInt()
        val kPulseDecayTime = 0.2e-3f * sampleRate
        val kPulseFilterTime = 0.1e-3f * sampleRate
        val kRetrigPulseDuration = 0.05f * sampleRate

        val scale = 0.001f / f0
        val q = 1500.0f * SynthDsp.semitonesToRatio(decay * 80.0f)
        val toneF = min(4.0f * f0 * SynthDsp.semitonesToRatio(tone * 108.0f), 1.0f)
        val exciterLeak = 0.08f * (tone + 0.25f)

        if (trigger) {
            pulseRemainingSamples = kTriggerPulseDuration
            fmPulseRemainingSamples = kFMPulseDuration
            pulseHeight = 3.0f + 7.0f * accent
            lpOut = 0.0f
        }

        // Q39 / Q40
        var currentPulse: Float
        if (pulseRemainingSamples > 0) {
            pulseRemainingSamples--
            currentPulse = if (pulseRemainingSamples > 0) pulseHeight else pulseHeight - 1.0f
            pulse = currentPulse
        } else {
            pulse *= 1.0f - 1.0f / kPulseDecayTime
            currentPulse = pulse
        }

        // C40 / ... / Diode
        pulseLp += (1.0f / kPulseFilterTime) * (currentPulse - pulseLp)
        currentPulse = diode((currentPulse - pulseLp) + currentPulse * 0.044f)

        // Q41 / Q42
        var fmPulse = 0.0f
        if (fmPulseRemainingSamples > 0) {
            fmPulseRemainingSamples--
            fmPulse = 1.0f
            retrigPulse = if (fmPulseRemainingSamples > 0) 0.0f else -0.8f
        } else {
            retrigPulse *= 1.0f - 1.0f / kRetrigPulseDuration
        }

        fmPulseLp += (1.0f / kPulseFilterTime) * (fmPulse - fmPulseLp)

        // Q43 leakage
        val punch = 0.7f + diode(10.0f * lpOut - 1.0f)

        // FM
        val attackFmVal = fmPulseLp * 1.7f * attackFm
        val selfFmVal = punch * 0.08f * selfFm
        val f = (f0 * (1.0f + attackFmVal + selfFmVal)).coerceIn(0f, 0.4f)

        // Resonator
        resonator.setFq(f, 1.0f + q * f)
        val excitation = (currentPulse - retrigPulse * 0.2f) * scale
        val outputs = resonator.process(excitation)
        val resonatorOut = outputs.bp
        lpOut = outputs.lp

        // Output Tone Filter
        toneLp += toneF * (currentPulse * exciterLeak + resonatorOut - toneLp)

        // Normalize output to reasonable range (bass drum can get very hot)
        return toneLp * 0.3f
    }
}
