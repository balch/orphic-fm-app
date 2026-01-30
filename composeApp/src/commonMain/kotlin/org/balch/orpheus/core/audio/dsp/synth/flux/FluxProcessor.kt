package org.balch.orpheus.core.audio.dsp.synth.flux

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
    
    // Output state
    private var currentVoltage = 0.5f
    private var previousSteps = 0.5f
    
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
    
    /**
     * Generate the next voltage when triggered by a gate/clock.
     * @return CV voltage in range 0-1 (represents pitch)
     */
    fun tick(): Float {
        // Generate raw random voltage with spread and bias
        val raw = generateRawVoltage()
        
        // Apply steps control (smooth → stepped → quantized)
        currentVoltage = applySteps(raw)
        
        return currentVoltage
    }
    
    private fun generateRawVoltage(): Float {
        // Get raw value from déjà-vu sequence
        val raw = randomSequence.nextValue(deterministic = false, value = 0.0f)
        
        // Apply spread and bias
        // Spread controls the distribution width
        // Bias skews toward high or low values
        val centered = raw - 0.5f
        val spread = centered * (spread * 2.0f)  // Expand or contract
        val biased = spread + bias
        
        return biased.coerceIn(0.0f, 1.0f)
    }
    
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
    
    // Getters for current state
    
    fun getCurrentVoltage(): Float = currentVoltage
    fun getSpread(): Float = spread
    fun getBias(): Float = bias
    fun getSteps(): Float = steps
    fun getDejaVu(): Float = dejaVu
    fun getLength(): Int = length
    fun getScaleIndex(): Int = scaleIndex
    
    /**
     * Reset the sequence (useful for syncing or manual reset)
     */
    fun reset() {
        randomSequence.reset()
        currentVoltage = 0.5f
    }
}
