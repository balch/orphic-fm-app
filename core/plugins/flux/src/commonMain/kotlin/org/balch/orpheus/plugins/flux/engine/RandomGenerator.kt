package org.balch.orpheus.plugins.flux.engine

/**
 * Pseudo-random generator used as a fallback when we need more random values
 * than available in the hardware RNG buffer.
 * 
 * Ported from Mutable Instruments Marbles.
 */
class RandomGenerator {
    private var state: UInt = 0u
    
    fun init(seed: UInt) {
        state = seed
    }
    
    fun mix(word: UInt) {
        // state = state xor word  // Commented out in original
    }
    
    fun getWord(): UInt {
        state = state * 1664525u + 1013904223u
        return state
    }
}
