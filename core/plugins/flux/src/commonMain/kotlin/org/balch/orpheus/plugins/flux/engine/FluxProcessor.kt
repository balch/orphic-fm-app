package org.balch.orpheus.plugins.flux.engine

import kotlin.math.pow

/**
 * Simplified Flux processor for melody generation.
 * 
 * This is a streamlined version focusing on:
 * - Quantized random melody CV output
 * - Déjà-vu loop control
 * - Scale selection
 * - Spread/bias/steps control
 * 
 * Ported from Mutable Instruments Marbles.
 */
class FluxProcessor(private val sampleRate: Float) {
    private val randomStream = RandomStream()
    private val randomSequence = RandomSequence()
    private val quantizer = Quantizer()
    
    // Current state
    private var spread = 0.5f  // Probability distribution width (0=narrow, 1=wide)
    private var bias = 0.5f    // Distribution bias (0=low notes, 1=high notes)
    private var steps = 0.5f   // Smooth (0) → S&H (0.5) → Quantized (1.0)
    private var dejaVu = 0.0f  // Loop probability (0=random, 0.5=locked, 1=permute)
    private var length = 8     // Loop length 1-16
    private var scaleIndex = 0 // Which scale to use
    
    private val timingGenerator = TimingGenerator()

    // Output state
    private var outX1 = 0.5f
    private var outX2 = 0.5f
    private var outX3 = 0.5f
    
    // Gate Outputs
    private var outT1 = 0.0f
    private var outT2 = 0.0f
    private var outT3 = 0.0f
    
    // Temp buffer for vector generation
    private val rawVector = FloatArray(3)
    
    // Scales (matches Marbles preset order)
    private val scales = arrayOf(
        Scale.major(),
        Scale.minor(),
        Scale.pentatonic(),
        Scale.phrygian(),
        Scale.wholeTone(),
        Scale.chromatic()
    )

    init {
        randomSequence.init(randomStream)
        quantizer.init(scales[0])
    }

    private var previousSteps = 0.5f

    /**
     * Generate the next voltage(s) when triggered by a gate/clock.
     * Updates internal state for X1, X2, X3.
     */
    fun tick() {
        // Update Timing State (Gates)
        // Since 'tick' is called ON a clock event, we treat this as the trigger
        timingGenerator.process(clockInput = true, rate = 0.5f, jitter = jitter, probability = probability)
        
        outT1 = if (timingGenerator.getT1()) 1.0f else 0.0f
        outT2 = if (timingGenerator.getT2()) 1.0f else 0.0f
        outT3 = if (timingGenerator.getT3()) 1.0f else 0.0f

        // Generate correlated random vector (X1, X2, X3)
        // This consumes one step of the Dejavu loop, ensuring all 3 are locked to the loop
        randomSequence.nextVector(rawVector, 3)
        
        // Process each channel
        outX1 = processChannel(rawVector[0])
        outX2 = processChannel(rawVector[1])
        outX3 = processChannel(rawVector[2])
    }
    
    /**
     * Called when clock is low/off to reset gates
     */
    fun tickClockOff() {
        timingGenerator.process(clockInput = false, rate = 0.5f, jitter = jitter, probability = probability)
        outT1 = 0.0f
        outT2 = 0.0f
        outT3 = 0.0f
    }
    
    fun getT1() = outT1
    fun getT2() = outT2
    fun getT3() = outT3
    
    private fun processChannel(raw: Float): Float {
        // Marbles uses a specific algorithm to mix 3 behaviors:
        // 1. Degenerate: Output is constant (equal to bias)
        // 2. Beta: Output is a bell curve around bias
        // 3. Bernoulli: Output is binary (0 or 1)
        
        // 1. Calculate mixing amounts based on spread
        // Spread < 0.05: Mostly degenerate
        // Spread > 0.95: Mostly bernoulli
        var degenerateAmount = 1.25f - spread * 25.0f
        var bernoulliAmount = spread * 25.0f - 23.75f
        
        degenerateAmount = degenerateAmount.coerceIn(0.0f, 1.0f)
        bernoulliAmount = bernoulliAmount.coerceIn(0.0f, 1.0f)
        
        // 2. Beta Distribution Approximation
        // We approximate the table-based BetaDistributionSample using a polynomial curve.
        // We essentially want to cluster 'raw' (uniform) around the center, then warp it by bias.
        
        // First, handle the variance (Spread) for the middle range.
        // As spread increases in the mid-range, we want wider variance.
        // We map spread 0.0..1.0 to an exponent that shapes the curve.
        // Marbles uses complex tables, but a simple power curve works for "bell" shape.
        // Pushing raw towards 0.5 helps clustering.
        val betaValue = approximateBeta(raw, spread, bias)
        
        var value = betaValue

        // 3. Apply mixing
        // Mix in Degenerate (snap to bias)
        // value = value + amount * (target - value) -> standard lerp
        value += degenerateAmount * (bias - value)
        
        // Mix in Bernoulli (snap to 0 or 1)
        val bernoulliValue = if (raw >= (1.0f - bias)) 0.999999f else 0.0f
        value += bernoulliAmount * (bernoulliValue - value)
        
        // Output scaling/offsetting (Standard Marbles range is -5V to +5V)
        // Here we keep it 0..1 internally for the engine to scale later, 
        // OR we just return 0..1 and let the Quantizer handle it.
        // The Marbles quantizer expects 0..1 range usually then scales.
        
        return applySteps(value.coerceIn(0.0f, 1.0f))
    }
    
    /**
     * Approximation of Mutable Instruments' BetaDistributionSample.
     * Maps uniform random [u] to a value distributed according to [spread] and [bias].
     */
    private fun approximateBeta(u: Float, spread: Float, bias: Float): Float {
        // This is a heuristic approximation to avoid 20KB of lookup tables.
        
        // 1. Center the uniform value (0..1 -> -1..1)
        var x = (u - 0.5f) * 2.0f
        
        // 2. Shape the distribution (Variance)
        // We use spread to modulate the width/clustering.
        val width = 0.1f + 0.9f * spread
        x *= width 
        
        // 3. Apply Bias warping (Skew)
        // Map centered X back to 0..1 then warp.
        var y = x * 0.5f + 0.5f
        
        // Warp y such that 0.5 maps to bias.
        // Power function: y' = y ^ k
        val safeBias = bias.coerceIn(0.01f, 0.99f)
        val k = kotlin.math.ln(safeBias) / kotlin.math.ln(0.5f)
        y = y.pow(k)
        
        return y
    }

    private fun applySteps(voltage: Float): Float {
        // Marbles Logic:
        // Steps < 0.5: Slew Limiter (Smoothness)
        // Steps >= 0.5: Quantizer
        
        if (steps >= 0.5f) {
            // Quantized Mode
            // The quantization amount (hysteresis) increases with steps
            // 0.5 -> 0.0 (continuous)
            // 1.0 -> 1.0 (hard steps)
            val qAmount = 2.0f * steps - 1.0f
            
             return quantizer.process(voltage, qAmount, hysteresis = true)
        } else {
            // Smooth / Slew Mode
            // In this mode, we return the raw voltage as the target.
            // Slew limiting (gliding between values) must be handled by the
            // continuous audio/control processor (e.g. JsynFluxUnit),
            // as this FluxProcessor 'tick' function is discrete and event-based.
            // The slew rate should depend on the 'steps' value (0.0 = slow slew, 0.5 = instant).
            return voltage
        }
    }
    
    // Parameter setters
    
    fun setSpread(spread: Float) {
        this.spread = spread.coerceIn(0.0f, 1.0f)
    }
    
    fun setBias(bias: Float) {
        this.bias = bias.coerceIn(0.0f, 1.0f)
    }
    
    fun setSteps(steps: Float) {
        this.steps = steps.coerceIn(0.0f, 1.0f)
        // If steps changed significantly, reinitialize quantizer
        if (kotlin.math.abs(steps - previousSteps) > 0.1f) {
            previousSteps = steps
        }
    }
    
    fun setDejaVu(dejaVu: Float) {
        this.dejaVu = dejaVu.coerceIn(0.0f, 1.0f)
        randomSequence.setDejaVu(dejaVu)
    }
    
    fun setLength(length: Int) {
        this.length = length.coerceIn(1, RandomSequence.DEJA_VU_BUFFER_SIZE)
        randomSequence.setLength(this.length)
    }
    
    fun setScale(index: Int) {
        scaleIndex = index.coerceIn(0, scales.size - 1)
        quantizer.init(scales[scaleIndex])
    }
    
    // Timing Generator Parameters
    private var jitter = 0.0f
    private var probability = 0.5f // Controls the T-Section Gate Probability (T1 vs T3 distribution)
                                   // Separate from the main voltage 'Bias' parameter as verified in FluxPanel.
                                   
    fun setJitter(jitter: Float) {
        this.jitter = jitter.coerceIn(0.0f, 1.0f)
    }
    
    fun setGateProbability(p: Float) {
        this.probability = p.coerceIn(0.0f, 1.0f)
    }
    
    // Getters for current state
    
    // Legacy support (defaults to X2)
    fun getCurrentVoltage(): Float = outX2

    fun getX1(): Float = outX1
    fun getX2(): Float = outX2
    fun getX3(): Float = outX3
    
    fun getSpread(): Float = spread
    fun getBias(): Float = bias
    fun getSteps(): Float = steps
    fun getDejaVu(): Float = dejaVu
    fun getLength(): Int = length
    fun getScaleIndex(): Int = scaleIndex
    fun getJitter(): Float = jitter
    fun getGateProbability(): Float = probability
    
    /**
     * Reset the sequence (useful for syncing or manual reset)
     */
    fun reset() {
        randomSequence.reset()
        outX1 = 0.5f
        outX2 = 0.5f
        outX3 = 0.5f
    }
}
