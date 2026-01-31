package org.balch.orpheus.plugins.grains.engine

/**
 * Pitch Shifter - a port of Mutable Instruments Clouds' pitch shifter.
 * 
 * This is a granular pitch shifter that uses two overlapping delay taps
 * with triangular crossfade to create smooth pitch shifting. The technique
 * is similar to the classic "phase vocoder lite" or dual-head tape pitch
 * shifting approach.
 * 
 * How it works:
 * - Audio is written to a delay buffer
 * - Two read heads (phase and half-phase) read from the buffer
 * - The read heads move at a different rate than the write head
 * - A triangular window crossfades between the two heads to hide discontinuities
 * 
 * SIZE controls the window size:
 * - Larger = smoother but more latency and potential smearing
 * - Smaller = more grainy/drilling sound but more responsive
 */
class PitchShifter(bufferSize: Int = 4096) {
    
    // Circular delay buffer (stereo interleaved: L, R, L, R, ...)
    private val buffer = FloatArray(bufferSize * 2)
    private val bufferMask = bufferSize - 1
    private var writeIndex = 0
    
    // Phase accumulator (0 to 1)
    private var phase = 0f
    
    // Window size in samples (controls granularity)
    private var size = 2047f
    private val maxSize = (bufferSize - 1).toFloat()
    
    // Pitch ratio (1.0 = unity, 2.0 = octave up, 0.5 = octave down)
    private var ratio = 1f
    
    fun init() {
        buffer.fill(0f)
        phase = 0f
        writeIndex = 0
        size = 2047f
        ratio = 1f
    }
    
    fun clear() {
        buffer.fill(0f)
    }
    
    /**
     * Set pitch ratio.
     * @param ratio 1.0 = no shift, 2.0 = octave up, 0.5 = octave down
     */
    fun setRatio(ratio: Float) {
        this.ratio = ratio.coerceIn(0.25f, 4f)
    }
    
    /**
     * Set window size from a normalized parameter (0-1).
     * Larger sizes = smoother but more latency.
     * @param size 0-1 normalized, maps to 128-2047 samples
     */
    fun setSize(size: Float) {
        // Map size to 128-2047 range with cubic curve for more control at low end
        val targetSize = 128f + (maxSize - 128f) * size * size * size
        // One-pole smoothing
        this.size += 0.05f * (targetSize - this.size)
    }
    
    /**
     * Process a stereo sample pair in-place.
     * @param left Left channel sample (modified in place)
     * @param right Right channel sample (modified in place)
     * @return Pair of (left, right) output samples
     */
    fun process(left: Float, right: Float): Pair<Float, Float> {
        // Write input to delay buffer (interleaved stereo)
        val wi = (writeIndex and bufferMask) * 2
        buffer[wi] = left
        buffer[wi + 1] = right
        writeIndex++
        
        // Update phase based on pitch ratio
        // phase += (1 - ratio) / size
        // When ratio > 1 (pitch up), phase decreases (read head catches up to write)
        // When ratio < 1 (pitch down), phase increases (read head falls behind write)
        phase += (1f - ratio) / size
        
        // Wrap phase to 0-1 range
        if (phase >= 1f) phase -= 1f
        if (phase <= 0f) phase += 1f
        
        // Calculate triangular crossfade window
        // tri = 0 at phase boundaries (0 and 1), max at center (0.5)
        val tri = 2f * if (phase >= 0.5f) (1f - phase) else phase
        
        // Calculate two read positions, 180 degrees apart
        val readPhase = phase * size
        var halfPhase = readPhase + size * 0.5f
        if (halfPhase >= size) halfPhase -= size
        
        // Calculate actual buffer indices (relative to current write position)
        val readDelay1 = readPhase.toInt()
        val readDelay2 = halfPhase.toInt()
        
        val frac1 = readPhase - readDelay1
        val frac2 = halfPhase - readDelay2
        
        // Read from delay buffer with linear interpolation
        val outL = interpolatedRead(0, readDelay1, frac1) * tri +
                   interpolatedRead(0, readDelay2, frac2) * (1f - tri)
        
        val outR = interpolatedRead(1, readDelay1, frac1) * tri +
                   interpolatedRead(1, readDelay2, frac2) * (1f - tri)
        
        return Pair(outL, outR)
    }
    
    /**
     * Process a block of stereo samples.
     */
    fun process(
        inputLeft: FloatArray,
        inputRight: FloatArray,
        outputLeft: FloatArray,
        outputRight: FloatArray,
        size: Int
    ) {
        for (i in 0 until size) {
            val (l, r) = process(inputLeft[i], inputRight[i])
            outputLeft[i] = l
            outputRight[i] = r
        }
    }
    
    /**
     * Read from delay buffer with linear interpolation.
     * @param channel 0 = left, 1 = right
     * @param delaySamples Integer delay in samples (from current write position)
     * @param frac Fractional part for interpolation (0-1)
     */
    private fun interpolatedRead(channel: Int, delaySamples: Int, frac: Float): Float {
        // Calculate buffer indices (going backwards from write position)
        val idx1 = ((writeIndex - 1 - delaySamples) and bufferMask) * 2 + channel
        val idx2 = ((writeIndex - 2 - delaySamples) and bufferMask) * 2 + channel
        
        val s1 = buffer[idx1]
        val s2 = buffer[idx2]
        
        // Linear interpolation
        return s1 + frac * (s2 - s1)
    }
}
