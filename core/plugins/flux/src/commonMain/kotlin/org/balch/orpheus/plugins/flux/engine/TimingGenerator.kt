package org.balch.orpheus.plugins.flux.engine
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

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
    private var jitterAmount = 0.0f
    
    // Internal counters
    private var phase = 0.0f
    private var frequency = 1.0f 
    private var nextTick = 0L
    
    // Gate States
    private var gateT1 = false // Probabilistic (Bias low)
    private var gateT2 = false // Main Clock
    private var gateT3 = false // Probabilistic (Bias high)
    
    // Random Source (shared or new)
    private val random = Random.Default

    /**
     * Advance the clock phase.
     * @param clockInput 24 PPQN or Gate input (0.0 or 1.0)
     * @param rate Rate parameter affects division/multiplication
     * @param jitter Jitter amount (0.0 - 1.0) for phase randomization (not fully implemented in block model)
     * @param probability Gate probability bias (0.0 = favored T3, 1.0 = favored T1, 0.5 = equal)
     */
    fun process(clockInput: Boolean, rate: Float, jitter: Float, probability: Float) {
        // T2 is the master clock (derived from input)
        gateT2 = clockInput
        
        // Jitter adds randomness to whether T1/T3 fire relative to T2.
        // In physical Marbles, Jitter affects the clock timing error.
        // Since we are currently clock-synced, we can't easily "move" the clock edges in time without offsets.
        // For now, Jitter will affect the probability calculation slightly or just be ignored until we do free-running clock.
        
        if (clockInput) {
            // Main clock T2 fired.
            // Decide if T1 and T3 fire based on probability (Bias) logic.
            
            // Logic:
            // T2 always fires on clock.
            // T1 fires if random > (1.0 - probability)
            // T3 fires if random < probability
            // If probability is 0.5, T1 > 0.5, T3 < 0.5. They are mutually exclusive in basic Bernoulli mode usually.
            
            // Let's implement Marbles "t" mode Bernoulli behavior:
            // "Bias controls the ratio of notes generated on t1 vs t3."
            // If Bias (probability) is 0.5: 50/50 split. 
            // If Bias is 1.0: T1 always fires, T3 never.
            // If Bias is 0.0: T3 always fires, T1 never.
            
            // Use probability parameter directly as the threshold
            val toss = random.nextFloat()
            
            // Bernoulli: Gates are mutually exclusive
            if (toss < probability) {
                gateT1 = true
                gateT3 = false
            } else {
                gateT1 = false
                gateT3 = true
            }
            
            // TODO: Use Jitter to introduce random skips or imperfections?
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
