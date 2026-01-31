package org.balch.orpheus.core.audio.dsp.synth.flux

import kotlin.random.Random

/**
 * Stream of random values from a hardware random source.
 * Uses Kotlin's Random as the entropy source.
 * 
 * Ported from Mutable Instruments Marbles.
 */
class RandomStream {
    private val random = Random.Default
    
    /**
     * Get a random float in range [0, 1)
     */
    fun getFloat(): Float {
        return random.nextFloat()
    }
    
    /**
     * Get a random word (UInt)
     */
    fun getWord(): UInt {
        return random.nextInt().toUInt()
    }
}
