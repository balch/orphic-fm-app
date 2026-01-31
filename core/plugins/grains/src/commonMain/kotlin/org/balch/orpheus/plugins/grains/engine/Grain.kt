package org.balch.orpheus.plugins.grains.engine

import kotlin.math.PI
import kotlin.math.cos

/**
 * A single grain for granular synthesis.
 * 
 * A grain is a short windowed segment of audio read from a buffer.
 * The envelope uses a Hann window for smooth attack/release.
 */
class Grain {
    var active = false
        private set
    
    private var phase = 0f           // Current position within grain (0 to duration)
    private var duration = 0f        // Total grain duration in samples
    private var readPosition = 0f   // Position in buffer to read from
    private var phaseIncrement = 1f  // Playback speed (1.0 = normal, 2.0 = octave up)
    private var amplitude = 1f       // Grain amplitude/gain
    
    /**
     * Initialize and start a new grain.
     * 
     * @param startPosition Position in buffer to start reading (samples)
     * @param grainDuration Duration of the grain in samples
     * @param pitchRatio Playback speed multiplier (1.0 = normal speed)
     * @param gain Amplitude multiplier for this grain
     * @param reverse If true, play grain backwards (from end to start)
     */
    fun trigger(
        startPosition: Float,
        grainDuration: Float,
        pitchRatio: Float = 1f,
        gain: Float = 1f,
        reverse: Boolean = false
    ) {
        this.readPosition = startPosition
        this.duration = grainDuration
        // For reverse playback, use negative phase increment
        this.phaseIncrement = if (reverse) -pitchRatio else pitchRatio
        this.amplitude = gain
        this.phase = 0f
        this.active = true
    }
    
    /**
     * Process one sample from this grain.
     * 
     * @param buffer Audio buffer to read from (stereo)
     * @param channelIndex 0 for left, 1 for right
     * @return Windowed sample value, or 0 if grain is inactive
     */
    fun process(buffer: List<AudioBuffer>, channelIndex: Int): Float {
        if (!active) return 0f
        
        // Check if grain is finished
        if (phase >= duration) {
            active = false
            return 0f
        }
        
        // Calculate envelope (Hann window)
        val normalizedPhase = phase / duration
        val envelope = 0.5f * (1f - cos(2f * PI.toFloat() * normalizedPhase))
        
        // Read from buffer with interpolation
        val bufferSize = buffer[channelIndex].size.toFloat()
        var pos = readPosition
        
        // Wrap position to buffer bounds
        while (pos < 0f) pos += bufferSize
        while (pos >= bufferSize) pos -= bufferSize
        
        val integral = pos.toInt()
        val fractional = ((pos - integral) * 65536f).toInt()
        
        val sample = buffer[channelIndex].readHermite(integral, fractional)
        
        // Advance phase and read position
        phase += 1f // Phase always advances forward from 0 to duration
        readPosition += phaseIncrement // Read position follows pitch and direction
        
        return sample * envelope * amplitude
    }
    
    /**
     * Check if grain is currently playing.
     */
    fun isActive(): Boolean = active
    
    /**
     * Stop the grain immediately.
     */
    fun stop() {
        active = false
    }
    
    /**
     * Get the current progress through the grain (0.0 to 1.0).
     */
    fun getProgress(): Float {
        return if (duration > 0f) (phase / duration).coerceIn(0f, 1f) else 1f
    }
}
