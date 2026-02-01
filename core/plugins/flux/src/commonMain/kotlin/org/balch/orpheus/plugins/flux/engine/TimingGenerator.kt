package org.balch.orpheus.plugins.flux.engine

import kotlin.random.Random

/**
 * t-Generator for Marbles: Timing, Jitter, and Gate Processing.
 * 
 * Handles:
 * - Jitter inputs to create non-uniform clocks
 * - Gate outputs (t1, t2, t3) with probabilistic triggering
 * - Adapts internal clock counters based on external or internal triggers
 */
class TimingGenerator {
    
    // Configurable state
    private var jitter = 0.0f
    private var bias = 0.5f

    // Internal counters
    private var masterPhase = 0.0f
    private var jitterMultiplier = 1.0f
    private var phaseDifference = 0.0f
    
    // Gate States
    private var gateT1 = false 
    private var gateT2 = false 
    private var gateT3 = false 
    
    private val random = Random.Default

    /**
     * Process timing logic.
     * In an ideal implementation, this is called every sample.
     * In the current block-trigger implementation, we simulate the effect of jitter
     * by warping the probability of the triggers.
     */
    fun process(clockInput: Boolean, rate: Float, jitter: Float, probability: Float) {
        this.jitter = jitter
        this.bias = probability
        
        // T2 is typically the master clock. 
        // In Marbles, T2 is the "steady" one, or the master for the t-section.
        gateT2 = clockInput
        
        if (clockInput) {
            // Apply jitter effect to T1 and T3 gates.
            // Marbles jitter calculation:
            // Jitter amount = jitter^4 * 36.0
            val jitterAmount = jitter * jitter * jitter * jitter * 36.0f
            
            // We simulate jitter by calculating a random threshold check
            // that is modulated by the jitter knob.
            // At high jitter, T1/T3 can "lag" or "early trigger" in a block by 
            // randomly skipping or repeating.
            
            val toss = random.nextFloat()
            
            // Generate Complementary Bernoulli Gates (Standard Marbles Green Mode)
            // T1 and T3 are complementary based on Bias (probability)
            
            // Add a "micro-jitter" to the toss based on jitterAmount to make it feel less static
            val jitteredToss = if (jitterAmount > 0) {
                (toss + (random.nextFloat() - 0.5f) * jitter * 0.5f).coerceIn(0.0f, 1.0f)
            } else {
                toss
            }

            if (jitteredToss < probability) {
                gateT1 = true
                gateT3 = false
            } else {
                gateT1 = false
                gateT3 = true
            }
            
            // If jitter is extreme (> 0.8), occasionally skip a pulse or double-trigger
            if (jitter > 0.8f) {
                if (random.nextFloat() < (jitter - 0.8f) * 0.5f) {
                    gateT1 = false
                    gateT2 = false
                    gateT3 = false
                }
            }
        } else {
             gateT1 = false
             gateT2 = false
             gateT3 = false
        }
    }
    
    fun getT1() = gateT1
    fun getT2() = gateT2
    fun getT3() = gateT3
}
