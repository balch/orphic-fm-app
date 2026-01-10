package org.balch.orpheus.core.audio.dsp.synth

import org.balch.orpheus.core.audio.SynthEngine

/**
 * Topographic Pattern Generator based on Mutable Instruments Grids.
 * 
 * Manages a 5x5 grid of drum patterns with interpolation.
 */
class DrumBeatsGenerator(
    private val synthEngine: SynthEngine
) {
    companion object Companion {
        const val NUM_PARTS = 3
        const val NUM_STEPS = 32
    }

    enum class OutputMode { DRUMS, EUCLIDEAN }

    var outputMode: OutputMode = OutputMode.DRUMS

    // Internal state
    private var x = 0.5f
    private var y = 0.5f
    private val densities = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var randomness = 0f
    
    private var step = 0
    private var clockCounter = 0
    private val resolution = 6 // 6 ticks per 16th note (24 PPQN)
    
    // Per-part settings
    // Lengths for Euclidean mode (1..32)
    val euclideanLengths = intArrayOf(16, 16, 16)
    
    // Internal state
    private val euclideanStep = intArrayOf(0, 0, 0)
    private val partPerturbation = IntArray(NUM_PARTS)

    fun setX(value: Float) { x = value.coerceIn(0f, 1f) }
    fun setY(value: Float) { y = value.coerceIn(0f, 1f) }
    fun setDensity(part: Int, value: Float) { densities[part] = value.coerceIn(0f, 1f) }
    fun setRandomness(value: Float) { randomness = value.coerceIn(0f, 1f) }
    
    // Set Euclidean length (raw 1..32)
    fun setEuclideanLength(part: Int, length: Int) {
        euclideanLengths[part] = length.coerceIn(1, 32)
    }

    fun tick() {
        clockCounter++
        if (clockCounter >= resolution) {
            clockCounter = 0

            // On step 0, calculate new perturbations (Grids logic: per pattern cycle)
            if (step == 0) {
                for (i in 0 until NUM_PARTS) {
                    // Random 0..255 scaled by randomness
                    val rnd = (kotlin.random.Random.nextFloat() * 255 * randomness).toInt()
                    partPerturbation[i] = rnd
                }
            }

            processStep()
            
            // Advance main step (for Drums mode)
            step = (step + 1) % NUM_STEPS
            
            // Advance Euclidean steps
            for (i in 0 until NUM_PARTS) {
                euclideanStep[i]++
                if (euclideanStep[i] >= euclideanLengths[i]) {
                    euclideanStep[i] = 0
                }
            }
        }
    }
    
    fun getCurrentStep(): Int = step
    
    fun reset() {
        step = 0
        clockCounter = 0
        for (i in 0 until NUM_PARTS) euclideanStep[i] = 0
    }

    private fun processStep() {
        if (outputMode == OutputMode.EUCLIDEAN) {
            processEuclidean()
        } else {
            processDrums()
        }
    }

    private fun processEuclidean() {
        for (p in 0 until NUM_PARTS) {
            val len = euclideanLengths[p]
            // Density 0..1 -> 0..31
            val densityIdx = (densities[p] * 31).toInt().coerceIn(0, 31)
            
            // Lookup address: (length - 1) * 32 + density
            // The LUT contains 32-bit bitmaps.
            val address = (len - 1) * 32 + densityIdx
            val patternBits = GridsPatternData.EUCLIDEAN[address]
            
            // Current step bit mask
            val stepMask = 1 shl euclideanStep[p]
            
            if ((patternBits and stepMask) != 0) {
                 synthEngine.triggerDrum(p, 1.0f)
            }
        }
    }

    private fun processDrums() {
        // Map X/Y (0..1) to 5x5 Grid coordinates (0..4)
        // We interpolate between i and i+1
        val xMapped = x * 4.0f
        val yMapped = y * 4.0f
        
        var xi = xMapped.toInt()
        var yi = yMapped.toInt()
        
        // Clamp to 0..3 for base index, so we always have a neighbor at +1
        // If we are exactly at 4.0, we clamp to 3 and use dx=1.0 (or just clamp input)
        if (xi >= 4) xi = 3
        if (yi >= 4) yi = 3
        
        val dx = (xMapped - xi).coerceIn(0f, 1f)
        val dy = (yMapped - yi).coerceIn(0f, 1f)
        
        // Get 4 nearest neighbor nodes
        // Map is [row][col] -> [y][x]
        val nodeIndexA = GridsPatternData.DRUM_MAP[yi][xi]
        val nodeIndexB = GridsPatternData.DRUM_MAP[yi][xi + 1]
        val nodeIndexC = GridsPatternData.DRUM_MAP[yi + 1][xi]
        val nodeIndexD = GridsPatternData.DRUM_MAP[yi + 1][xi + 1]

        val nodeA = GridsPatternData.getNode(nodeIndexA)
        val nodeB = GridsPatternData.getNode(nodeIndexB)
        val nodeC = GridsPatternData.getNode(nodeIndexC)
        val nodeD = GridsPatternData.getNode(nodeIndexD)

        for (p in 0 until NUM_PARTS) {
            // Planar layout: Instrument offset is p * 32
            val offset = p * NUM_STEPS + step
            
            // Get values from 4 nodes (unsigned byte 0..255)
            val valA = nodeA[offset].toInt() and 0xFF
            val valB = nodeB[offset].toInt() and 0xFF
            val valC = nodeC[offset].toInt() and 0xFF
            val valD = nodeD[offset].toInt() and 0xFF
            
            // Bilinear Interpolation
            // mix(a, b, x) + dy * (mix(c, d, x) - mix(a, b, x))
            // mix(a, b, x) = a + x * (b - a)
            
            val topRow = valA + dx * (valB - valA)
            val bottomRow = valC + dx * (valD - valC)
            val interpolated = topRow + dy * (bottomRow - topRow)
            
            // Apply perturbation
            var level = interpolated.toInt()
            val purt = partPerturbation[p]
            if (level < 255 - purt) {
                level += purt
            } else {
                level = 255
            }
            
            val value = level / 255.0f
            
            // Check density threshold
            if (value > (1.0f - densities[p])) {
                synthEngine.triggerDrum(p, 1.0f)
            }
        }
    }
}
