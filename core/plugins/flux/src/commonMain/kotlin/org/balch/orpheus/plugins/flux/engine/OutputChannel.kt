// Copyright 2015 Emilie Gillet.
// Ported to Kotlin from Mutable Instruments Marbles random/output_channel.cc.
// License: MIT

package org.balch.orpheus.plugins.flux.engine

/** Random generation output channel.
 *
 *  Per-sample processing:
 *  - Detects ramp phase wraparound -> triggers new voltage from RandomSequence
 *  - Applies beta distribution sampling for voltage shaping
 *  - Applies quantization with per-sample steps interpolation
 *  - Uses LagProcessor for smooth mode (steps < 0.5)
 *  - Register mode with reacquisition counter for external CV */
class OutputChannel {
    private var spread = 0.5f
    private var bias = 0.5f
    private var steps = 0.5f
    private var scaleIndex = 0

    private var registerMode = false
    private var registerValue = 0f
    private var registerTransposition = 0f

    private var previousSteps = 0f
    private var previousPhase = 0f
    private var reacquisitionCounter = 0

    private var previousVoltage = 0f
    private var voltage = 0f
    private var quantizedVoltage = 0f

    private var scaleOffset = ScaleOffset(3f, 0f)
    private val lagProcessor = LagProcessor()
    private val quantizers = Array(6) { Quantizer() }

    fun init() {
        spread = 0.5f
        bias = 0.5f
        steps = 0.5f
        scaleIndex = 0

        registerMode = false
        registerValue = 0f
        registerTransposition = 0f

        previousSteps = 0f
        previousPhase = 0f
        reacquisitionCounter = 0

        previousVoltage = 0f
        voltage = 0f
        quantizedVoltage = 0f

        scaleOffset = ScaleOffset(3f, 0f)
        lagProcessor.init()

        val defaultScale = Scale.chromatic()
        for (i in 0 until 6) {
            quantizers[i].init(defaultScale)
        }
    }

    fun loadScale(i: Int, scale: Scale) {
        quantizers[i].init(scale)
    }

    fun setSpread(spread: Float) { this.spread = spread }
    fun setBias(bias: Float) { this.bias = bias }
    fun setSteps(steps: Float) { this.steps = steps }
    fun setScaleIndex(i: Int) { scaleIndex = i }
    fun setRegisterMode(mode: Boolean) { registerMode = mode }
    fun setRegisterValue(value: Float) { registerValue = value }
    fun setRegisterTransposition(t: Float) { registerTransposition = t }
    fun setScaleOffset(so: ScaleOffset) { scaleOffset = so }

    fun quantize(voltage: Float, amount: Float): Float {
        return quantizers[scaleIndex].process(voltage, amount, false)
    }

    private fun generateNewVoltage(randomSequence: RandomSequence): Float {
        val u = randomSequence.nextValue(registerMode, registerValue)

        return if (registerMode) {
            10f * (u - 0.5f) + registerTransposition
        } else {
            var degenerateAmount = 1.25f - spread * 25f
            var bernoulliAmount = spread * 25f - 23.75f
            degenerateAmount = degenerateAmount.coerceIn(0f, 1f)
            bernoulliAmount = bernoulliAmount.coerceIn(0f, 1f)

            var value = Distributions.betaDistributionSample(u, spread, bias)
            val bernoulliValue = if (u >= (1f - bias)) 0.999999f else 0f

            value += degenerateAmount * (bias - value)
            value += bernoulliAmount * (bernoulliValue - value)
            scaleOffset(value)
        }
    }

    /** Process a block of samples.
     *  @param randomSequence the sequence to draw random values from
     *  @param phase input ramp phase array
     *  @param phaseOffset offset into phase array
     *  @param output output voltage array
     *  @param outputOffset offset into output array
     *  @param size number of samples
     *  @param stride output stride (for interleaved output) */
    fun process(
        randomSequence: RandomSequence,
        phase: FloatArray,
        phaseOffset: Int,
        output: FloatArray,
        outputOffset: Int,
        size: Int,
        stride: Int
    ) {
        // ParameterInterpolator for steps: linear interpolation from previousSteps to steps
        val stepsStart = previousSteps
        val stepsIncrement = (steps - previousSteps) / size.toFloat()
        previousSteps = steps

        // Reacquisition for register mode (CV settling)
        if (reacquisitionCounter > 0) {
            reacquisitionCounter--
            val u = randomSequence.rewriteValue(registerValue)
            voltage = 10f * (u - 0.5f) + registerTransposition
            quantizedVoltage = quantize(voltage, 2f * steps - 1f)
        }

        for (s in 0 until size) {
            val currentSteps = stepsStart + stepsIncrement * s
            val currentPhase = phase[phaseOffset + s]

            if (currentPhase < previousPhase) {
                previousVoltage = voltage
                voltage = generateNewVoltage(randomSequence)
                lagProcessor.resetRamp()
                quantizedVoltage = quantize(voltage, 2f * currentSteps - 1f)
                if (registerMode) {
                    reacquisitionCounter = NUM_REACQUISITIONS
                }
            }

            val outIdx = outputOffset + s * stride
            if (currentSteps >= 0.5f) {
                output[outIdx] = quantizedVoltage
            } else {
                val smoothness = 1f - 2f * currentSteps
                output[outIdx] = lagProcessor.process(voltage, smoothness, currentPhase)
            }
            previousPhase = currentPhase
        }
    }

    companion object {
        const val NUM_REACQUISITIONS = 20
    }
}
