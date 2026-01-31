package org.balch.orpheus.core.audio.dsp.synth

import kotlin.random.Random

/**
 * 808 Hi-Hat model ported from Mutable Instruments Plaits.
 */
class MetallicHiHat {
    private val phases = UIntArray(6) { 0u }
    private val colorationFilter = SynthDsp.StateVariableFilter()
    private val hpf = SynthDsp.StateVariableFilter()
    
    private var envelope = 0f
    private var noiseClock = 0f
    private var noiseSample = 0f
    
    private val sampleRate = SynthDsp.SAMPLE_RATE

    private val RATIOS = floatArrayOf(
        1.0f, 1.304f, 1.466f, 1.787f, 1.932f, 2.536f
    )

    fun init() {
        colorationFilter.init()
        hpf.init()
        reset()
    }

    fun reset() {
        phases.fill(0u)
        envelope = 0f
        noiseClock = 0f
        noiseSample = 0f
        colorationFilter.reset()
        hpf.reset()
    }

    /**
     * Process one sample.
     * @param trigger true on the first sample of a hit
     * @param accent 0..1
     * @param f0 normalized base frequency (Hz / SampleRate)
     * @param tone 0..1
     * @param decay 0..1
     * @param noisiness 0..1
     */
    fun process(
        trigger: Boolean,
        accent: Float,
        f0: Float,
        tone: Float,
        decay: Float,
        noisiness: Float
    ): Float {
        val envelopeDecay = 1.0f - 0.003f * SynthDsp.semitonesToRatio(-decay * 84.0f)
        val cutDecay = 1.0f - 0.0025f * SynthDsp.semitonesToRatio(-decay * 36.0f)
        
        if (trigger) {
            envelope = (1.5f + 0.5f * (1.0f - decay)) * (0.3f + 0.7f * accent)
        }

        // 1. Square Noise (6 oscillators)
        var squareNoise = 0u
        for (i in 0 until 6) {
            val f = (f0 * 2.0f * RATIOS[i]).coerceIn(0f, 0.499f)
            val increment = (f * 4294967296.0).toLong().toUInt()
            phases[i] += increment
            squareNoise += (phases[i] shr 31)
        }
        var hatSignal = 0.33f * squareNoise.toFloat() - 1.0f

        // 2. Coloration Filter (BPF)
        val cutoff = (150.0f / sampleRate * SynthDsp.semitonesToRatio(tone * 72.0f)).coerceIn(0f, 16000.0f / sampleRate)
        colorationFilter.setFq(cutoff, 1.0f)
        hatSignal = colorationFilter.processBp(hatSignal)

        // 3. Clocked Noise (Noisiness)
        val n2 = noisiness * noisiness
        val noiseF = (f0 * (16.0f + 16.0f * (1.0f - n2))).coerceIn(0f, 0.5f)
        noiseClock += noiseF
        if (noiseClock >= 1.0f) {
            noiseClock -= 1.0f
            noiseSample = Random.nextFloat() - 0.5f
        }
        hatSignal += n2 * (noiseSample - hatSignal)

        // 4. VCA Envelope (Two-stage)
        envelope *= if (envelope > 0.5f) envelopeDecay else cutDecay
        hatSignal *= envelope

        // 5. HPF
        hpf.setFq(cutoff, 0.5f)
        hatSignal = hpf.processHp(hatSignal)
        
        return hatSignal
    }
    
    // Actually, I'll need to update SynthDsp.kt to include processHp
}
