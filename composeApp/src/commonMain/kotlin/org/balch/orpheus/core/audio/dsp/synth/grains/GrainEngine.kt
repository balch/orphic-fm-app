package org.balch.orpheus.core.audio.dsp.synth.grains

import kotlin.random.Random

/**
 * Granular synthesis engine managing multiple overlapping grains.
 * 
 * This engine:
 * - Maintains a pool of grains (typically 8-16)
 * - Schedules new grains at regular intervals based on density
 * - Reads from a circular audio buffer
 * - Outputs the sum of all active grains
 */
class GrainEngine(
    private val maxGrains: Int = 12
) {
    private val grains = Array(maxGrains) { Grain() }
    private var grainSpawnCounter = 0f
    private var numChannels = 2
    private val random = Random.Default
    
    // Grain scheduling state
    private var currentReadPosition = 0f
    private var targetDelay = 0f
    
    fun init(numChannels: Int) {
        this.numChannels = numChannels
        grains.forEach { it.stop() }
        grainSpawnCounter = 0f
        currentReadPosition = 0f
    }
    
    /**
     * Process one sample of granular synthesis output.
     * 
     * @param buffer Audio buffer to read grains from
     * @param writeHead Current write position in buffer
     * @param grainSize Grain duration in samples (controlled by SIZE parameter)
     * @param density Grain density/overlap factor - higher = more overlapping grains (0-1)
     * @param delayTime Delay time in samples (controlled by POSITION parameter)
     * @param pitchRatio Pitch shift ratio (controlled by PITCH parameter)
     * @param spray Random position deviation in samples (0 = no randomness)
     * @param mode Processing mode (0=Granular, 1=Reverse, 2=Shimmer, etc.)
     * @param channelIndex 0 for left, 1 for right
     * @return Mixed output sample from all active grains
     */
    fun process(
        buffer: List<AudioBuffer>,
        writeHead: Int,
        grainSize: Float,
        density: Float,
        delayTime: Float,
        pitchRatio: Float = 1f,
        spray: Float = 0f,
        mode: Int = 0,
        channelIndex: Int = 0
    ): Float {
        // Update target read position (delayed from write head)
        targetDelay = delayTime
        
        // Smooth the read position to avoid clicks
        val error = targetDelay - currentReadPosition
        currentReadPosition += 0.0001f * error
        
        // Calculate grain spawn interval based on density
        // density 0.0 = sparse (one grain every grainSize samples)
        // density 1.0 = dense (new grain every grainSize/4 samples = 4x overlap)
        val overlapFactor = 1f + density * 7f // 1x to 8x overlap
        val spawnInterval = grainSize / overlapFactor
        
        // Increment spawn counter
        grainSpawnCounter += 1f
        
        // Spawn new grain if interval reached
        if (grainSpawnCounter >= spawnInterval && spawnInterval > 0f) {
            grainSpawnCounter = 0f
            spawnGrain(
                buffer = buffer,
                writeHead = writeHead,
                grainSize = grainSize,
                delayTime = currentReadPosition,
                pitchRatio = pitchRatio,
                spray = spray,
                mode = mode
            )
        }
        
        // Accumulate output from all active grains
        var output = 0f
        var activeCount = 0
        
        for (grain in grains) {
            if (grain.isActive()) {
                output += grain.process(buffer, channelIndex)
                activeCount++
            }
        }
        
        // Normalize by active grain count to prevent level buildup
        // Use soft normalization to avoid division by zero
        val normalization = 1f / (1f + activeCount * 0.3f)
        
        return output * normalization
    }
    
    /**
     * Spawn a new grain from the buffer.
     */
    private fun spawnGrain(
        buffer: List<AudioBuffer>,
        writeHead: Int,
        grainSize: Float,
        delayTime: Float,
        pitchRatio: Float,
        spray: Float,
        mode: Int
    ) {
        // Find an inactive grain slot
        val inactiveGrain = grains.firstOrNull { !it.isActive() } ?: return
        
        val bufferSize = buffer[0].size.toFloat()
        
        // Calculate read position with optional spray (randomness)
        val sprayOffset = if (spray > 0f) {
            (random.nextFloat() - 0.5f) * 2f * spray
        } else {
            0f
        }
        
        // For reverse mode, we offset the start position by the grain size
        // so that the grain plays backwards and *ends* near the target delay point.
        val reverseOffset = if (mode == 1) grainSize else 0f
        
        // Read position = write head - delay - spray offset + reverseOffset
        var readPos = writeHead.toFloat() - delayTime + sprayOffset + reverseOffset
        
        // Wrap to buffer bounds
        while (readPos < 0f) readPos += bufferSize
        while (readPos >= bufferSize) readPos -= bufferSize
        
        // Clamp grain size to reasonable bounds
        val clampedGrainSize = grainSize.coerceIn(64f, bufferSize * 0.25f)
        
        // Determine grain behavior based on mode
        val reverse: Boolean
        val pitchMultiplier: Float
        
        when (mode) {
            1 -> {
                // REVERSE mode - play backwards
                reverse = true
                pitchMultiplier = 1f
            }
            2 -> {
                // SHIMMER mode - octave up (+12 semitones = 2x speed)
                reverse = false
                pitchMultiplier = 2f
            }
            3 -> {
                // SPECTRAL mode - time-stretch (half speed for formant preservation effect)
                reverse = false
                pitchMultiplier = 0.5f // Half speed = time-stretch
            }
            4 -> {
                // KARPLUS-STRONG mode - string resonance (use pitch to create harmonic grains)
                reverse = false
                // Use input pitch ratio as-is, will sound more pitched/harmonic
                pitchMultiplier = 1f + (pitchRatio * 0.5f) // Slight pitch variation for string character
            }
            else -> {
                // GRANULAR and other modes - normal playback
                reverse = false
                pitchMultiplier = 1f
            }
        }
        
        // Apply pitch multiplier to the input pitch ratio
        val finalPitchRatio = pitchRatio * pitchMultiplier
        
        // Trigger the grain
        inactiveGrain.trigger(
            startPosition = readPos,
            grainDuration = clampedGrainSize,
            pitchRatio = finalPitchRatio,
            gain = 1f,
            reverse = reverse
        )
    }
    
    /**
     * Get the number of currently active grains.
     */
    fun getActiveGrainCount(): Int {
        return grains.count { it.isActive() }
    }
    
    /**
     * Stop all grains immediately.
     */
    fun stop() {
        grains.forEach { it.stop() }
        grainSpawnCounter = 0f
    }
}
