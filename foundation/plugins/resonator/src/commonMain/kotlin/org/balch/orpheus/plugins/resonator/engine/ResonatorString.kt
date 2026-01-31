package org.balch.orpheus.plugins.resonator.engine
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

import kotlin.math.abs

/**
 * Karplus-Strong String Synthesizer ported from Mutable Instruments Rings (rings/dsp/string.cc).
 * 
 * Uses a comb filter (delay line) with a damping filter in the feedback loop
 * to simulate the vibrating string behavior. This creates realistic plucked
 * string sounds with natural decay characteristics.
 * 
 * Parameters:
 * - frequency: Normalized frequency (Hz / SampleRate)
 * - brightness: High frequency content (affects filter tone)
 * - damping: Decay time (0 = long sustain, 1 = quick decay)
 * - position: Pick/pluck position (affects comb filtering color)
 * 
 * @param delayLineSize Size of delay line in samples (default 2048 ~= 46ms at 44.1kHz)
 */
class ResonatorString(private val delayLineSize: Int = 2048) {
    
    // Delay line for the main string
    private val delayLine = FloatArray(delayLineSize)
    private var writePos = 0
    
    // Damping filter in feedback loop
    private val dampingFilter = SynthDsp.DampingFilter()
    
    // DC blocker to remove DC offset buildup
    private var dcBlockerState = 0f
    private val dcBlockerCoeff = 1f - 20f / SynthDsp.SAMPLE_RATE
    
    // Parameters
    var frequency: Float = 220f / SynthDsp.SAMPLE_RATE
        set(value) { field = value.coerceIn(0.0001f, 0.49f) }
    
    var brightness: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var damping: Float = 0.3f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    var position: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }
    
    // Internal state
    private var delay = 1f
    private var clampedPosition = 0.5f
    private var outSample0 = 0f
    private var outSample1 = 0f
    private var auxSample0 = 0f
    private var auxSample1 = 0f
    
    /**
     * Initialize the string.
     */
    fun init() {
        delayLine.fill(0f)
        writePos = 0
        dampingFilter.init()
        dcBlockerState = 0f
        
        delay = 1f / frequency
        clampedPosition = 0.5f
        outSample0 = 0f
        outSample1 = 0f
        auxSample0 = 0f
        auxSample1 = 0f
    }
    
    /**
     * Reset the string state (clear delay line).
     */
    fun reset() {
        delayLine.fill(0f)
        dampingFilter.reset()
        dcBlockerState = 0f
        outSample0 = 0f
        outSample1 = 0f
        auxSample0 = 0f
        auxSample1 = 0f
    }
    
    /**
     * Read from the delay line with linear interpolation.
     */
    private fun readDelay(delaySamples: Float): Float {
        val readPos = writePos - delaySamples
        val intReadPos = readPos.toInt()
        val frac = readPos - intReadPos
        
        // Wrap indices to delay line size
        val idx0 = (intReadPos % delayLineSize + delayLineSize) % delayLineSize
        val idx1 = (idx0 + 1) % delayLineSize
        
        return delayLine[idx0] + frac * (delayLine[idx1] - delayLine[idx0])
    }
    
    /**
     * Write to the delay line.
     */
    private fun writeDelay(sample: Float) {
        delayLine[writePos] = sample
        writePos = (writePos + 1) % delayLineSize
    }
    
    /**
     * DC blocker to remove DC offset.
     */
    private fun dcBlock(input: Float): Float {
        val output = input - dcBlockerState
        dcBlockerState = input * (1f - dcBlockerCoeff) + dcBlockerState * dcBlockerCoeff
        return output * dcBlockerCoeff
    }
    
    /**
     * Process one sample through the string.
     * 
     * @param input Excitation signal (pluck impulse)
     * @return Pair of (main output, comb-filtered aux output)
     */
    fun process(input: Float): Pair<Float, Float> {
        // Calculate delay in samples from frequency
        var delaySamples = 1f / frequency
        delaySamples = delaySamples.coerceIn(4f, (delayLineSize - 4).toFloat())
        
        // Smooth delay parameter
        delay += 0.1f * (delaySamples - delay)
        
        // Calculate damping coefficient
        // Low frequency damping based on damping parameter
        val lfDamping = damping * (2f - damping)
        val rt60 = 0.07f * SynthDsp.semitonesToRatio(lfDamping * 96f) * SynthDsp.SAMPLE_RATE
        val rt60Base2_12 = (-120f * delay / rt60).coerceAtLeast(-127f)
        val dampingCoeff = SynthDsp.semitonesToRatio(rt60Base2_12)
        
        // Brightness affects filter cutoff
        val brightnessSquared = brightness * brightness
        
        // Configure the damping filter
        dampingFilter.configure(dampingCoeff, brightnessSquared)
        
        // Calculate pick position for comb delay (affects even/odd balance)
        val pickPosition = 0.5f - 0.98f * abs(position - 0.5f)
        clampedPosition += 0.1f * (pickPosition - clampedPosition)
        val combDelay = delay * clampedPosition
        
        // Read from delay line (Karplus-Strong core)
        var s = readDelay(delay - 1f) // -1 for FIR filter delay
        
        // Add input excitation
        s += input
        
        // Apply damping filter
        s = dampingFilter.process(s)
        
        // DC block to prevent buildup
        s = dcBlock(s)
        
        // Write back to delay line
        writeDelay(s)
        
        // Store samples for interpolation
        outSample1 = outSample0
        auxSample1 = auxSample0
        outSample0 = s
        auxSample0 = readDelay(combDelay)
        
        return Pair(SynthDsp.softClip(outSample0), SynthDsp.softClip(auxSample0))
    }
    
    /**
     * Process a block of samples.
     */
    fun processBlock(
        input: FloatArray,
        outMain: FloatArray,
        outAux: FloatArray,
        size: Int
    ) {
        for (i in 0 until size) {
            val (main, aux) = process(input[i])
            outMain[i] = main
            outAux[i] = aux
        }
    }
}
