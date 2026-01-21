package org.balch.orpheus.core.audio.dsp.synth.grains

/**
 * Diffuser - All-pass diffusion network.
 * 
 * A port of Mutable Instruments Clouds' diffuser. This is a cascade of 4 all-pass
 * filters per channel that "smears" transients, creating a blurry/washy texture
 * without the tail of a reverb.
 * 
 * The delay times are carefully chosen prime-ish numbers to avoid resonances:
 * Left:  126, 180, 269, 444 samples
 * Right: 151, 205, 245, 405 samples
 * 
 * At 44.1kHz, this gives diffusion times of roughly 3-10ms per stage.
 */
class Diffuser {
    
    // All-pass filter coefficient (controls feedback amount)
    private val kap = 0.625f
    
    // Delay lines for left channel (4 cascaded all-pass filters)
    private val delayL1 = AllPassDelay(126)
    private val delayL2 = AllPassDelay(180)
    private val delayL3 = AllPassDelay(269)
    private val delayL4 = AllPassDelay(444)
    
    // Delay lines for right channel (4 cascaded all-pass filters)
    private val delayR1 = AllPassDelay(151)
    private val delayR2 = AllPassDelay(205)
    private val delayR3 = AllPassDelay(245)
    private val delayR4 = AllPassDelay(405)
    
    // Wet/dry mix amount (0 = dry, 1 = fully diffused)
    private var amount = 0f
    
    fun init() {
        delayL1.clear()
        delayL2.clear()
        delayL3.clear()
        delayL4.clear()
        delayR1.clear()
        delayR2.clear()
        delayR3.clear()
        delayR4.clear()
        amount = 0f
    }
    
    /**
     * Set the diffusion amount.
     * @param amount 0 = no diffusion (dry), 1 = full diffusion
     */
    fun setAmount(amount: Float) {
        this.amount = amount.coerceIn(0f, 1f)
    }
    
    /**
     * Process a block of stereo audio in-place.
     */
    fun process(
        left: FloatArray,
        right: FloatArray,
        size: Int
    ) {
        if (amount < 0.001f) return // Skip if no diffusion
        
        for (i in 0 until size) {
            // Left channel: cascade through 4 all-pass filters
            var wetL = left[i]
            wetL = delayL1.process(wetL, kap)
            wetL = delayL2.process(wetL, kap)
            wetL = delayL3.process(wetL, kap)
            wetL = delayL4.process(wetL, kap)
            
            // Mix wet into dry
            // in += amount * (wet - in)
            left[i] += amount * (wetL - left[i])
            
            // Right channel: cascade through 4 all-pass filters
            var wetR = right[i]
            wetR = delayR1.process(wetR, kap)
            wetR = delayR2.process(wetR, kap)
            wetR = delayR3.process(wetR, kap)
            wetR = delayR4.process(wetR, kap)
            
            // Mix wet into dry
            right[i] += amount * (wetR - right[i])
        }
    }
    
    /**
     * Process interleaved stereo audio in-place.
     * @param buffer Interleaved stereo buffer (L, R, L, R, ...)
     * @param offset Starting index
     * @param size Number of stereo sample pairs to process
     */
    fun processInterleaved(
        buffer: FloatArray,
        offset: Int,
        size: Int
    ) {
        if (amount < 0.001f) return
        
        var idx = offset
        for (i in 0 until size) {
            // Left channel
            var wetL = buffer[idx]
            wetL = delayL1.process(wetL, kap)
            wetL = delayL2.process(wetL, kap)
            wetL = delayL3.process(wetL, kap)
            wetL = delayL4.process(wetL, kap)
            buffer[idx] += amount * (wetL - buffer[idx])
            
            // Right channel
            var wetR = buffer[idx + 1]
            wetR = delayR1.process(wetR, kap)
            wetR = delayR2.process(wetR, kap)
            wetR = delayR3.process(wetR, kap)
            wetR = delayR4.process(wetR, kap)
            buffer[idx + 1] += amount * (wetR - buffer[idx + 1])
            
            idx += 2
        }
    }
}

/**
 * Simple all-pass delay line for the diffuser.
 * 
 * An all-pass filter passes all frequencies equally but shifts phase.
 * The formula is:
 *   output = -kap * input + delayedSample
 *   delayLine.write(input + kap * delayedSample)
 * 
 * This creates a comb-like effect that smears transients in time.
 */
private class AllPassDelay(size: Int) {
    private val buffer = FloatArray(size)
    private val mask = size - 1 // Assumes power of 2... but we won't actually use mask
    private val delaySize = size
    private var writeIndex = 0
    
    fun clear() {
        buffer.fill(0f)
        writeIndex = 0
    }
    
    /**
     * Process one sample through the all-pass filter.
     * 
     * @param input Input sample
     * @param kap All-pass coefficient (typically 0.5-0.7)
     * @return Output sample
     */
    fun process(input: Float, kap: Float): Float {
        // Read the delayed sample (from the end of the delay line)
        val readIndex = (writeIndex + 1) % delaySize
        val delayed = buffer[readIndex]
        
        // All-pass formula:
        // output = delayed + (-kap) * input
        // write = input + kap * delayed
        val output = delayed - kap * input
        buffer[writeIndex] = input + kap * delayed
        
        // Advance write pointer
        writeIndex = (writeIndex + 1) % delaySize
        
        return output
    }
}
