package org.balch.orpheus.core.audio.dsp.synth.warps

import kotlin.math.abs
import kotlin.math.exp

/**
 * Main processor for Warps. Matches the Modulator class in original source.
 */
class WarpsProcessor {
    val parameters = WarpsParameters()
    private val previousParameters = WarpsParameters()
    
    private val amplifier1 = SaturatingAmplifier()
    private val amplifier2 = SaturatingAmplifier()
    
    // Buffers for block processing
    private var carrierBuffer = FloatArray(0)
    private var modulatorBuffer = FloatArray(0)
    private var mainOutputBuffer = FloatArray(0)
    private var auxOutputBuffer = FloatArray(0)
    
    // Parameter smoothing for click-free algorithm changes
    private var smoothedAlgorithm = 0f
    private var smoothedTimbre = 0f
    private var algorithmSmoothingCoeff = 0.995f // Smooth over ~50ms at 48kHz
    private var timbreSmoothingCoeff = 0.998f    // Slightly slower for timbre
    
    fun init(sampleRate: Float) {
        amplifier1.init()
        amplifier2.init()
        
        // Calculate smoothing coefficients based on sample rate
        // Time constant ~20ms for algorithm, ~50ms for timbre
        val algorithmTimeMs = 20f
        val timbreTimeMs = 50f
        algorithmSmoothingCoeff = exp(-1000f / (algorithmTimeMs * sampleRate))
        timbreSmoothingCoeff = exp(-1000f / (timbreTimeMs * sampleRate))
        
        // Initial parameter state
        previousParameters.modulationAlgorithm = 0f
        previousParameters.modulationParameter = 0f
        previousParameters.channelDrive[0] = 0f
        previousParameters.channelDrive[1] = 0f
        
        smoothedAlgorithm = 0f
        smoothedTimbre = 0f
    }

    private fun ensureBuffers(size: Int) {
        if (carrierBuffer.size < size) {
            carrierBuffer = FloatArray(size)
            modulatorBuffer = FloatArray(size)
            mainOutputBuffer = FloatArray(size)
            auxOutputBuffer = FloatArray(size)
        }
    }

    fun process(
        inputLeft: FloatArray,
        inputRight: FloatArray,
        outputLeft: FloatArray,
        outputRight: FloatArray,
        size: Int
    ) {
        ensureBuffers(size)
        
        // Apply parameter smoothing for click-free changes
        val targetAlgorithm = parameters.modulationAlgorithm
        val targetTimbre = parameters.modulationParameter
        
        // Smooth the algorithm parameter to prevent clicks
        val algorithmStart = smoothedAlgorithm
        for (i in 0 until size) {
            smoothedAlgorithm += (targetAlgorithm - smoothedAlgorithm) * (1f - algorithmSmoothingCoeff)
            smoothedTimbre += (targetTimbre - smoothedTimbre) * (1f - timbreSmoothingCoeff)
        }
        val algorithmEnd = smoothedAlgorithm
        
        // 0.0: use cross-modulation algorithms. 1.0f: use vocoder.
        var vocoderAmount = (algorithmEnd - 0.7f) * 20.0f + 0.5f
        vocoderAmount = vocoderAmount.coerceIn(0f, 1f)
        
        // Aux output represents the modulation or secondary signal in MI logic
        auxOutputBuffer.fill(0f)
        
        // 1. Process VCA and saturation stage
        amplifier1.process(
            parameters.channelDrive[0],
            1.0f - vocoderAmount,
            inputLeft,
            carrierBuffer,
            auxOutputBuffer,
            size
        )
        
        amplifier2.process(
            parameters.channelDrive[1],
            1.0f - vocoderAmount,
            inputRight,
            modulatorBuffer,
            auxOutputBuffer,
            size
        )
        
        // 2. Render internal carrier (REMOVED)
        
        // 3. Apply Modulation Algorithms with smoothed parameters
        if (vocoderAmount < 0.5f) {
            // Use smoothed algorithm value for click-free transitions
            val algo = (algorithmEnd * 8.0f).coerceIn(0f, 5.999f)
            val prevAlgo = (algorithmStart * 8.0f).coerceIn(0f, 5.999f)
            
            val algoIntegral = algo.toInt()
            val algoFractional = algo - algoIntegral
            
            var prevAlgoFractional = prevAlgo - prevAlgo.toInt()
            if (algoIntegral != prevAlgo.toInt()) {
                prevAlgoFractional = algoFractional
            }
            
            // Use smoothed timbre for the modulation parameter
            processXmod(
                algoIntegral,
                prevAlgoFractional,
                algoFractional,
                smoothedTimbre * (1.0f + getSkew(algorithmStart) * (smoothedTimbre - 1.0f)),
                smoothedTimbre * (1.0f + getSkew(algorithmEnd) * (smoothedTimbre - 1.0f)),
                modulatorBuffer,
                carrierBuffer,
                mainOutputBuffer,
                size
            )
        } else {
            // 4. Vocoder Case
            // TODO: Implement Vocoder process
            // For now, just pass through modulator as placeholder
            for (i in 0 until size) {
                mainOutputBuffer[i] = modulatorBuffer[i]
            }
        }
        
        // 5. Cross-fade to raw modulator for transition
        val transitionGain = 2.0f * (if (vocoderAmount < 0.5f) vocoderAmount else 1.0f - vocoderAmount)
        if (transitionGain != 0f) {
            for (i in 0 until size) {
                mainOutputBuffer[i] += transitionGain * (modulatorBuffer[i] - mainOutputBuffer[i])
            }
        }
        
        // 6. Write to outputs
        for (i in 0 until size) {
            outputLeft[i] = mainOutputBuffer[i]
            outputRight[i] = auxOutputBuffer[i] * 0.5f // Adjusted gain to match MI logic
        }
        
        // Save previous parameters
        previousParameters.modulationAlgorithm = parameters.modulationAlgorithm
        previousParameters.modulationParameter = parameters.modulationParameter
        previousParameters.channelDrive[0] = parameters.channelDrive[0]
        previousParameters.channelDrive[1] = parameters.channelDrive[1]
    }

    /**
     * Calculate the skew factor for the modulation parameter based on algorithm position.
     * Matches the original MI implementation for non-linear parameter response.
     */
    private fun getSkew(algorithm: Float): Float {
        return when {
            algorithm <= 0.125f -> algorithm * 8.0f
            algorithm >= 0.625f -> 1.0f
            algorithm >= 0.5f -> (0.625f - algorithm) * 8.0f
            else -> 0f
        }
    }

    private fun processXmod(
        algo: Int,
        balanceStart: Float,
        balanceEnd: Float,
        paramStart: Float,
        paramEnd: Float,
        mod: FloatArray,
        carrier: FloatArray,
        out: FloatArray,
        size: Int
    ) {
        val step = 1.0f / size
        var balance = balanceStart
        var param = paramStart
        val balanceInc = (balanceEnd - balanceStart) * step
        val paramInc = (paramEnd - paramStart) * step
        
        for (i in 0 until size) {
            val m = mod[i]
            val c = carrier[i]
            
            val a = xmod(algo, m, c, param)
            val b = xmod(algo + 1, m, c, param)
            
            out[i] = a + (b - a) * balance
            
            balance += balanceInc
            param += paramInc
        }
    }

    private fun xmod(algo: Int, x1: Float, x2: Float, param: Float): Float {
        return when (algo) {
            0 -> xmodXfade(x1, x2, param)
            1 -> xmodFold(x1, x2, param)
            2 -> xmodAnalogRingMod(x1, x2, param)
            3 -> xmodDigitalRingMod(x1, x2, param)
            4 -> xmodXor(x1, x2, param)
            5 -> xmodCompare(x1, x2, param)
            else -> x1 // NOP
        }
    }

    private fun diode(x: Float): Float {
        val sign = if (x > 0f) 1f else -1f
        var deadZone = abs(x) - 0.667f
        deadZone += abs(deadZone)
        deadZone *= deadZone
        return 0.04324765822726063f * deadZone * sign
    }

    private fun xmodXfade(x1: Float, x2: Float, p: Float): Float {
        // Linear fade for now, should use LUT_XFADE
        return x1 * (1.0f - p) + x2 * p
    }

    private fun xmodFold(x1: Float, x2: Float, p: Float): Float {
        var sum = x1 + x2 + x1 * x2 * 0.25f
        sum *= 0.02f + p
        // Triangle fold approximation: 2 * abs(frac(x + 0.5) - 0.5) * 2 - 1
        return (abs(((sum + 0.5f) % 1.0f + 1.0f) % 1.0f - 0.5f) - 0.25f) * 4f
    }

    private fun xmodAnalogRingMod(mod: Float, carrier: Float, p: Float): Float {
        val c = carrier * 2.0f
        var ring = diode(mod + c) + diode(mod - c)
        ring *= (4.0f + p * 24.0f)
        return ring.coerceIn(-1f, 1f) // SoftLimit approximation
    }

    private fun xmodDigitalRingMod(x1: Float, x2: Float, p: Float): Float {
        val ring = 4.0f * x1 * x2 * (1.0f + p * 8.0f)
        return ring / (1.0f + abs(ring))
    }

    private fun xmodXor(x1: Float, x2: Float, p: Float): Float {
        val s1 = (x1 * 32768).toInt()
        val s2 = (x2 * 32768).toInt()
        val mod = (s1 xor s2).toFloat() / 32768f
        val sum = (x1 + x2) * 0.7f
        return sum + (mod - sum) * p
    }

    private fun xmodCompare(mod: Float, carrier: Float, p: Float): Float {
        val x = p * 2.995f
        val xInt = x.toInt()
        val xFrac = x - xInt
        
        val direct = if (mod < carrier) mod else carrier
        val window = if (abs(mod) > abs(carrier)) mod else carrier
        val window2 = if (abs(mod) > abs(carrier)) abs(mod) else -abs(carrier)
        val threshold = if (carrier > 0.05f) carrier else mod
        
        val sequence = floatArrayOf(direct, threshold, window, window2)
        val a = sequence[xInt]
        val b = sequence[xInt + 1]
        return a + (b - a) * xFrac
    }
}
