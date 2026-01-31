package org.balch.orpheus.core.audio.dsp.synth

import kotlin.random.Random

/**
 * 808 Snare Drum model ported from Mutable Instruments Plaits.
 */
class AnalogSnareDrum {
    companion object {
        private const val NUM_MODES = 2 // Plan specifies "Dual SVFs"
        private val MODE_FREQUENCIES = floatArrayOf(1.00f, 2.00f)
    }

    private val resonators = Array(NUM_MODES) { SynthDsp.StateVariableFilter() }
    private val noiseFilter = SynthDsp.StateVariableFilter()
    
    private var pulseRemainingSamples = 0
    private var pulse = 0f
    private var pulseHeight = 0f
    private var pulseLp = 0f
    private var noiseEnvelope = 0f
    
    private val sampleRate = SynthDsp.SAMPLE_RATE

    fun init() {
        resonators.forEach { it.init() }
        noiseFilter.init()
        reset()
    }

    fun reset() {
        pulseRemainingSamples = 0
        pulse = 0f
        pulseHeight = 0f
        pulseLp = 0f
        noiseEnvelope = 0f
        resonators.forEach { it.reset() }
        noiseFilter.reset()
    }

    /**
     * Process one sample.
     * @param trigger true on the first sample of a hit
     * @param accent 0..1
     * @param f0 normalized base frequency (Hz / SampleRate)
     * @param tone 0..1
     * @param decay 0..1
     * @param snappy 0..1
     */
    fun process(
        trigger: Boolean,
        accent: Float,
        f0: Float,
        tone: Float,
        decay: Float,
        snappy: Float
    ): Float {
        val decayXt = decay * (1.0f + decay * (decay - 1.0f))
        val kTriggerPulseDuration = (1.0e-3f * sampleRate).toInt()
        val kPulseDecayTime = 0.1e-3f * sampleRate
        val q = 2000.0f * SynthDsp.semitonesToRatio(decayXt * 84.0f)
        
        val noiseEnvelopeDecay = 1.0f - 0.0017f * SynthDsp.semitonesToRatio(-decay * (50.0f + snappy * 10.0f))
        val exciterLeak = snappy * (2.0f - snappy) * 0.1f
        
        val adjustedSnappy = (snappy * 1.1f - 0.05f).coerceIn(0f, 1f)
        
        if (trigger) {
            pulseRemainingSamples = kTriggerPulseDuration
            pulseHeight = 3.0f + 7.0f * accent
            noiseEnvelope = 2.0f
        }

        // Q45 / Q46
        var currentPulse: Float
        if (pulseRemainingSamples > 0) {
            pulseRemainingSamples--
            currentPulse = if (pulseRemainingSamples > 0) pulseHeight else pulseHeight - 1.0f
            pulse = currentPulse
        } else {
            pulse *= 1.0f - 1.0f / kPulseDecayTime
            currentPulse = pulse
        }

        // Shell
        pulseLp += 0.75f * (currentPulse - pulseLp)
        
        var shell = 0.0f
        val gains = floatArrayOf(
            1.5f + (1.0f - tone) * (1.0f - tone) * 4.5f,
            2.0f * tone + 0.15f
        )

        for (i in 0 until NUM_MODES) {
            val f = (f0 * MODE_FREQUENCIES[i]).coerceIn(0f, 0.499f)
            val modeQ = 1.0f + f * (if (i == 0) q else q * 0.25f)
            resonators[i].setFq(f, modeQ)
            
            val excitation = if (i == 0) {
                (currentPulse - pulseLp) + 0.006f * currentPulse
            } else {
                0.026f * currentPulse
            }
            
            shell += gains[i] * (resonators[i].processBp(excitation) + excitation * exciterLeak)
        }
        
        // Soft saturation instead of hard clip to prevent clicks
        shell = if (kotlin.math.abs(shell) < 1f) {
            shell
        } else {
            kotlin.math.sign(shell) * (1f - 1f / (1f + kotlin.math.abs(shell)))
        }

        // Noise / Snappy
        var noise = (Random.nextFloat() * 2.0f - 1.0f).coerceAtLeast(0f)
        noiseEnvelope *= noiseEnvelopeDecay
        noise *= noiseEnvelope * adjustedSnappy * 2.0f

        val fNoise = (f0 * 16.0f).coerceIn(0f, 0.499f)
        noiseFilter.setFq(fNoise, 1.0f + fNoise * 1.5f)
        val filteredNoise = noiseFilter.processBp(noise)

        return filteredNoise + shell * (1.0f - adjustedSnappy)
    }
}
