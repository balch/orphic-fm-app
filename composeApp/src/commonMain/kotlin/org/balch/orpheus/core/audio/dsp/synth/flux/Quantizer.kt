package org.balch.orpheus.core.audio.dsp.synth.flux

import kotlin.math.floor

/**
 * Variable resolution quantizer.
 * 
 * Quantizes input voltages to scale degrees based on an "amount" parameter.
 * When amount = 0, no quantization.
 * When amount = 1, full quantization with all scale notes.
 * As amount increases beyond 1, progressively removes less-important notes.
 * 
 * Ported from Mutable Instruments Marbles.
 */
class Quantizer {
    companion object {
        const val NUM_THRESHOLDS = 7
    }
    
    private data class Level(
        val bitmask: Int,  // Bitmask of active degrees
        val first: Int,    // Index of first active degree
        val last: Int      // Index of last active degree
    )
    
    private val voltage = FloatArray(Scale.MAX_DEGREES)
    private val levels = Array(NUM_THRESHOLDS) { Level(0, 0, 0) }
    private val feedback = FloatArray(NUM_THRESHOLDS)
    
    private var baseInterval = 1.0f
    private var baseIntervalReciprocal = 1.0f
    private var numDegrees = 0
    private var levelQuantizerState = 0.0f
    
    fun init(scale: Scale) {
        val n = scale.degrees.size
        
        // Validate scale data
        if (n == 0 || n > Scale.MAX_DEGREES || scale.baseInterval == 0.0f) {
            return
        }
        
        numDegrees = n
        baseInterval = scale.baseInterval
        baseIntervalReciprocal = 1.0f / scale.baseInterval
        
        // Find second largest weight threshold
        var secondLargestThreshold = 0
        for (i in 0 until n) {
            voltage[i] = scale.degrees[i].voltage
            if (scale.degrees[i].weight != 255 && scale.degrees[i].weight >= secondLargestThreshold) {
                secondLargestThreshold = scale.degrees[i].weight
            }
        }
        
        // Define threshold levels
        val thresholds = intArrayOf(0, 16, 32, 64, 128, 192, 255)
        
        // Adjust second-to-last threshold if there's a close second-place note
        if (secondLargestThreshold > 192) {
            thresholds[NUM_THRESHOLDS - 2] = secondLargestThreshold
        }
        
        // Build level bitmasks
        for (t in 0 until NUM_THRESHOLDS) {
            var bitmask = 0
            var first = 0xff
            var last = 0
            for (i in 0 until n) {
                if (scale.degrees[i].weight >= thresholds[t]) {
                    bitmask = bitmask or (1 shl i)
                    if (first == 0xff) first = i
                    last = i
                }
            }
            levels[t] = Level(bitmask, first, last)
        }
        
        feedback.fill(0.0f)
    }
    
    /**
     * Process an input voltage and quantize it to the scale.
     * 
     * @param value Input voltage (typically 0-1 representing pitch CV)
     * @param amount Quantization amount (0 = bypassed, 0-7 = progressive quantization)
     * @param hysteresis Enable hysteresis to prevent flickering
     * @return Quantized voltage
     */
    fun process(value: Float, amount: Float, hysteresis: Boolean): Float {
        // Simple hysteresis quantizer for level selection
        val level = processLevelQuantizer(amount, NUM_THRESHOLDS + 1)
        var quantizedVoltage = value
        
        if (level > 0) {
            val levelIndex = level - 1
            val l = levels[levelIndex]
            
            // Safety check: if no notes meet this threshold (bitmask is 0), skip quantization
            if (l.bitmask == 0) {
                return value
            }

            var inputValue = value
            if (hysteresis) {
                inputValue += feedback[levelIndex]
            }
            
            // Split into octave and fractional parts
            val note = inputValue * baseIntervalReciprocal
            var noteIntegral = floor(note).toInt()
            var noteFractional = note - noteIntegral
            
            if (inputValue < 0.0f) {
                noteIntegral -= 1
                noteFractional += 1.0f
            }
            noteFractional *= baseInterval
            
            // Search for tightest upper/lower bound in active notes
            // Ensure indices are safe (though bitmask check above covers most cases)
            val safeLast = l.last.coerceIn(0, voltage.lastIndex)
            val safeFirst = l.first.coerceIn(0, voltage.lastIndex)
            
            var a = voltage[safeLast] - baseInterval
            var b = voltage[safeFirst] + baseInterval
            
            var bitmask = l.bitmask
            for (i in 0 until numDegrees) {
                if ((bitmask and 1) != 0) {
                    val v = voltage[i]
                    if (noteFractional > v) {
                        a = v
                    } else {
                        b = v
                        break
                    }
                }
                bitmask = bitmask shr 1
            }
            
            // Snap to nearest available note
            quantizedVoltage = if (noteFractional < (a + b) * 0.5f) a else b
            quantizedVoltage += noteIntegral.toFloat() * baseInterval
            feedback[levelIndex] = (quantizedVoltage - value) * 0.25f
        }
        
        return quantizedVoltage
    }
    
    /**
     * Simple hysteresis quantizer for level selection.
     * Mimics stmlib::HysteresisQuantizer2
     */
    private fun processLevelQuantizer(value: Float, numLevels: Int): Int {
        val scaledValue = value * (numLevels - 1).toFloat()
        val level = scaledValue.toInt().coerceIn(0, numLevels - 1)
        
        // Simple hysteresis: only change if we've moved at least 0.3 units
        val distance = kotlin.math.abs(scaledValue - levelQuantizerState)
        if (distance > 0.3f) {
            levelQuantizerState = scaledValue
        }
        
        return levelQuantizerState.toInt().coerceIn(0, numLevels - 1)
    }
}
