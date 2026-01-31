package org.balch.orpheus.plugins.flux.engine

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
        // Apply spread and bias
        val centered = raw - 0.5f
        val spreadVal = centered * (spread * 2.0f)
        val biased = spreadVal + bias
        val preQuantized = biased.coerceIn(0.0f, 1.0f)
        
        // Apply steps/quantization
        return applySteps(preQuantized)
    }

    // Getters for current state
    fun getX1(): Float = outX1
    fun getX2(): Float = outX2
    fun getX3(): Float = outX3
    
    private fun applySteps(voltage: Float): Float {
        return when {
            steps < 0.33f -> {
                // Smooth mode: lag processor (for now, just return voltage)
                // TODO: Implement lag processing
                voltage
            }
            steps < 0.67f -> {
                // Sample & Hold mode: stepped but not quantized
                voltage
            }
            else -> {
                // Quantized mode: snap to scale
                val quantizeAmount = ((steps - 0.67f) / 0.33f) * 7.0f  // Map to 0-7
                quantizer.process(voltage, quantizeAmount, hysteresis = true)
            }
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
    private var probability = 0.5f // Replaces "bias" for gates in original Marbles spec? 
                                   // Actually Marbles reuses "Bias" for t-section probability when input is plugged into clock?
                                   // But user asked to add "jitter and bias" specifically. 
                                   // Since "Bias" knob already exists and controls voltage bias, we should probably add a dedicated Probability/Gate Bias knob
                                   // OR reuse the existing Bias knob if we want true Marbles behavior (context dependent).
                                   // Given the UI panel has separate sections, let's make them separate parameters to be explicit.
                                   
    fun setJitter(jitter: Float) {
        this.jitter = jitter.coerceIn(0.0f, 1.0f)
    }
    
    fun setGateProbability(p: Float) {
        this.probability = p.coerceIn(0.0f, 1.0f)
    }
    
    // Getters for current state
    
    // Legacy support (defaults to X2)
    fun getCurrentVoltage(): Float = outX2
    
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
