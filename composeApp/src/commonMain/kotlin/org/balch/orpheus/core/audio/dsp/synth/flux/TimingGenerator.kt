package org.balch.orpheus.core.audio.dsp.synth.flux

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
     */
    fun process(clockInput: Boolean, rate: Float, jitter: Float) {
        // T2 is the master clock (derived from input)
        gateT2 = clockInput
        
        // Jitter adds randomness to whether T1/T3 fire relative to T2, 
        // or effectively "moves" the clock edge in time (harder in block processing without lookahead,
        // so we simulate "Bernoulli Gate" behavior usually found in t-section).
        
        if (clockInput) {
            // Main clock T2 fired.
            // Decide if T1 and T3 fire based on jitter/probability logic.
            
            // In Marbles "t" mode:
            // Bias (at 12 o'clock) -> Toggle coin toss
            // Bias (low) -> t1 more likely?
            // Bias (high) -> t3 more likely?
            
            // For now, let's implement the "Bernoulli Gate" style logic often associated with this:
            // Jitter acts as the "deviance" or probability source.
            
            // Logic:
            // T2 always fires on clock.
            // T1 fires if random > 0.5 (or controlled by bias)
            // T3 fires if random < 0.5
            
            // Using a simplified model for now:
            gateT1 = random.nextFloat() > 0.5f
            gateT3 = !gateT1
            
            // If jitter is 0, maybe they lock? Or this IS the jitter behavior?
            // Marbles manual: "t2 is the main clock. t1 and t3 are rhythmically derived from t2."
            // "Bias controls the ratio of notes generated on t1 vs t3."
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
