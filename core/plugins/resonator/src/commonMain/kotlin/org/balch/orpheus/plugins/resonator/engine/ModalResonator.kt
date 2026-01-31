package org.balch.orpheus.plugins.resonator.engine
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Modal Resonator ported from Mutable Instruments Rings (rings/dsp/resonator.cc).
 * 
 * Uses a bank of state-variable filters (SVFs) to simulate the resonant modes
 * of vibrating structures like plates, bars, and strings. Each filter represents
 * one partial/harmonic in the resonant spectrum.
 * 
 * Parameters:
 * - frequency: Normalized base frequency (Hz / SampleRate)
 * - structure: Controls inharmonicity (0=tubes, 0.25=strings, 1=bells/plates)
 * - brightness: High frequency content / material (wood→glass→steel)
 * - damping: Decay time (100ms to 10s)
 * - position: Excitation point (affects even/odd partial balance)
 * 
 * @param maxModes Maximum number of resonant modes (default 64, reduce for CPU)
 */
class ModalResonator(private val maxModes: Int = 64) {
    
    // SVF filter bank - one per resonant mode
    private val filters = Array(maxModes) { SynthDsp.StateVariableFilter() }
    
    // Parameters (normalized 0-1 or Hz/SampleRate for frequency)
    var frequency: Float = 220f / SynthDsp.SAMPLE_RATE
        set(value) { field = value.coerceIn(0.00001f, 0.49f) }
    
    var structure: Float = 0.25f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var brightness: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var damping: Float = 0.3f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var position: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var resolution: Int = maxModes
        set(value) {
            // Must be even for odd/even partial splitting
            var adjusted = value - (value and 1)
            field = min(adjusted, maxModes)
        }
    
    private var previousPosition = 0f
    
    /**
     * Initialize the resonator.
     */
    fun init() {
        filters.forEach { it.init() }
        previousPosition = position
    }
    
    /**
     * Reset filter states (call on new note).
     */
    fun reset() {
        filters.forEach { it.reset() }
    }
    
    /**
     * Compute filter coefficients based on current parameters.
     * Ported from Resonator::ComputeFilters() in rings/dsp/resonator.cc
     * 
     * @return Number of active modes
     */
    private fun computeFilters(): Int {
        // Get stiffness from structure parameter (controls partial stretching)
        val stiffness = RingsTables.interpolate(RingsTables.LUT_STIFFNESS, structure, 256f)
        
        var harmonic = frequency
        var stretchFactor = 1.0f
        
        // Q factor from damping (500 * exponential mapping)
        var q = 500.0f * RingsTables.interpolate(RingsTables.LUT_4_DECADES, damping, 256f)
        
        // SAFETY LIMIT: Reduce Q at high brightness/structure to prevent squelching
        // Brightness and structure both increase harshness, so we damp Q more as they rise
        val safetyDamping = 1f - (brightness * 0.4f + structure * 0.3f).coerceIn(0f, 0.7f)
        q *= safetyDamping

        // Brightness affects high frequency rolloff
        val brightnessAttenuation = (1f - structure).let { it * it * it * it * it * it * it * it }
        val brightnessAdj = brightness * (1f - 0.2f * brightnessAttenuation)
        val qLoss = brightnessAdj * (2f - brightnessAdj) * 0.85f + 0.15f
        val qLossDampingRate = structure * (2f - structure) * 0.1f
        
        var qLossCurrent = qLoss
        var numModes = 0
        
        for (i in 0 until min(maxModes, resolution)) {
            val partialFrequency = (harmonic * stretchFactor).coerceAtMost(0.49f)
            
            if (partialFrequency < 0.49f) {
                numModes = i + 1
            }
            
            // Set filter frequency and Q
            filters[i].setFq(partialFrequency, 1f + partialFrequency * q)
            
            // Stretch factor accumulates for inharmonicity
            stretchFactor += stiffness
            if (stiffness < 0f) {
                // Prevent folding for negative stiffness (tubes)
                stretchFactor = (stretchFactor * 0.93f).coerceAtLeast(0.01f)
            } else {
                // Extra partials at high frequencies
                stretchFactor *= 0.98f
            }
            
            // Q loss prevents highest partials from decaying too fast
            qLossCurrent += qLossDampingRate * (1f - qLossCurrent)
            harmonic += frequency
            q *= qLossCurrent
        }
        
        return numModes
    }
    
    /**
     * Process one sample through the resonator.
     * 
     * @param input Excitation signal
     * @return Pair of (odd partials, even partials) for stereo output
     */
    fun process(input: Float): Pair<Float, Float> {
        val numModes = computeFilters()
        
        // Cosine oscillator for position-based amplitude modulation
        // Position controls where on the structure we "pluck" - affects partial levels
        val scaledInput = input * 0.125f
        
        var odd = 0f
        var even = 0f
        
        // Amplitude modulation based on position (cosine-based)
        // This creates the characteristic hollow/full sound based on pick position
        val posPhase = position * PI.toFloat() * 2f
        var ampPhase = 0f
        val ampIncrement = posPhase / numModes.coerceAtLeast(1)
        
        var i = 0
        while (i < numModes) {
            // Compute amplitude for this partial based on position
            val amp1 = cos(ampPhase)
            ampPhase += ampIncrement
            val amp2 = cos(ampPhase)
            ampPhase += ampIncrement
            
            // Process through bandpass filters
            odd += amp1 * filters[i].processBp(scaledInput)
            i++
            if (i < numModes) {
                even += amp2 * filters[i].processBp(scaledInput)
                i++
            }
        }
        
        return Pair(SynthDsp.softClip(odd), SynthDsp.softClip(even))
    }
    
    /**
     * Process a block of samples.
     * 
     * @param input Input buffer
     * @param outOdd Output buffer for odd partials
     * @param outEven Output buffer for even partials
     * @param size Number of samples to process
     */
    fun processBlock(
        input: FloatArray,
        outOdd: FloatArray,
        outEven: FloatArray,
        size: Int
    ) {
        for (s in 0 until size) {
            val (odd, even) = process(input[s])
            outOdd[s] = odd
            outEven[s] = even
        }
    }
}
