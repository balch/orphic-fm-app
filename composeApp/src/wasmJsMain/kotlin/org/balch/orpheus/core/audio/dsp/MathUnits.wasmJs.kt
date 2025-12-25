package org.balch.orpheus.core.audio.dsp

import org.khronos.webgl.Float32Array
import org.khronos.webgl.set

/**
 * WASM actual implementations of math/utility units using Web Audio API.
 */

actual interface Multiply : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

/**
 * Multiply using a GainNode.
 * InputA is the signal, InputB controls the gain.
 * For true audio-rate multiplication, both would need to be signals,
 * but Web Audio's GainNode handles this when inputB is connected as audio.
 */
class WebAudioMultiply(private val context: AudioContext) : Multiply {
    private val gainNode = context.createGain().also { it.gain.value = 0f }
    
    override val inputA: AudioInput = WebAudioNodeInput(gainNode, 0, context)
    override val inputB: AudioInput = WebAudioParamInput(gainNode.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(gainNode)
}

actual interface Add : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

/**
 * Add by routing two signals to the same gain node (summing).
 */
class WebAudioAdd(private val context: AudioContext) : Add {
    private val sumNode = context.createGain().also { it.gain.value = 1f }
    
    // Two input gain nodes that both connect to the sum
    private val inputAGain = context.createGain().also { 
        it.gain.value = 1f
        it.connect(sumNode)
    }
    private val inputBGain = context.createGain().also { 
        it.gain.value = 1f
        it.connect(sumNode)
    }
    
    override val inputA: AudioInput = WebAudioNodeInput(inputAGain, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(inputBGain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(sumNode)
}

actual interface MultiplyAdd : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
    actual val inputC: AudioInput
}

/**
 * MultiplyAdd: output = (inputA * inputB) + inputC
 */
class WebAudioMultiplyAdd(private val context: AudioContext) : MultiplyAdd {
    // First multiply A * B
    private val multiplyGain = context.createGain().also { it.gain.value = 0f }
    
    // Then add C
    private val sumNode = context.createGain().also { it.gain.value = 1f }
    private val inputCGain = context.createGain().also { 
        it.gain.value = 1f
        it.connect(sumNode)
    }
    
    init {
        multiplyGain.connect(sumNode)
    }
    
    override val inputA: AudioInput = WebAudioNodeInput(multiplyGain, 0, context)
    override val inputB: AudioInput = WebAudioParamInput(multiplyGain.gain, context)
    override val inputC: AudioInput = WebAudioNodeInput(inputCGain, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(sumNode)
}

actual interface PassThrough : AudioUnit {
    actual val input: AudioInput
}

class WebAudioPassThrough(private val context: AudioContext) : PassThrough {
    private val gainNode = context.createGain().also { it.gain.value = 1f }
    
    override val input: AudioInput = WebAudioNodeInput(gainNode, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(gainNode)
}

actual interface SineOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

/**
 * Base class for Web Audio oscillators
 */
abstract class WebAudioOscillatorBase(
    protected val context: AudioContext,
    oscType: String
) : AudioUnit {
    protected val oscillator = context.createOscillator().also {
        it.type = oscType
        it.frequency.value = 440f
        it.start()
    }
    
    protected val gainNode = context.createGain().also { it.gain.value = 1f }
    
    init {
        oscillator.connect(gainNode)
    }
    
    open val frequency: AudioInput = WebAudioParamInput(oscillator.frequency, context)
    open val amplitude: AudioInput = WebAudioParamInput(gainNode.gain, context)
    override val output: AudioOutput = WebAudioNodeOutput(gainNode)
}

class WebAudioSineOscillator(context: AudioContext) : WebAudioOscillatorBase(context, "sine"), SineOscillator {
    override val frequency: AudioInput get() = super.frequency
    override val amplitude: AudioInput get() = super.amplitude
}

actual interface TriangleOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class WebAudioTriangleOscillator(context: AudioContext) : WebAudioOscillatorBase(context, "triangle"), TriangleOscillator {
    override val frequency: AudioInput get() = super.frequency
    override val amplitude: AudioInput get() = super.amplitude
}

actual interface SquareOscillator : AudioUnit {
    actual val frequency: AudioInput
    actual val amplitude: AudioInput
}

class WebAudioSquareOscillator(context: AudioContext) : WebAudioOscillatorBase(context, "square"), SquareOscillator {
    override val frequency: AudioInput get() = super.frequency
    override val amplitude: AudioInput get() = super.amplitude
}

actual interface Minimum : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

/**
 * Minimum of two signals using Web Audio nodes.
 * Formula: min(a,b) = 0.5*(a+b) - 0.5*|a-b|
 * 
 * Implementation:
 * 1. Sum = a + b (using sumGain)
 * 2. Diff = a - b (using diffGain with inverted inputB)
 * 3. AbsDiff = |a - b| (using WaveShaperNode with abs curve)
 * 4. Result = 0.5*Sum - 0.5*AbsDiff
 */
class WebAudioMinimum(private val context: AudioContext) : Minimum {
    // Create input splitters to route inputs to both sum and diff paths
    private val inputASplitter = context.createGain().also { it.gain.value = 1f }
    private val inputBSplitter = context.createGain().also { it.gain.value = 1f }
    
    // Input A gain nodes (routed from splitter)
    private val inputAToSum = context.createGain().also { it.gain.value = 1f }
    private val inputAToDiff = context.createGain().also { it.gain.value = 1f }
    
    // Input B gain nodes (routed from splitter)
    private val inputBToSum = context.createGain().also { it.gain.value = 1f }
    private val inputBToDiff = context.createGain().also { it.gain.value = -1f } // Inverted for subtraction
    
    // Sum: a + b
    private val sumGain = context.createGain().also { it.gain.value = 1f }
    
    // Diff: a - b
    private val diffGain = context.createGain().also { it.gain.value = 1f }
    
    // Absolute value of difference using WaveShaper
    private val absShaper = context.createWaveShaper().apply {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1  // -1 to 1
            curve[i] = kotlin.math.abs(x)
        }
        this.curve = curve
        this.oversample = "none"
    }
    
    // Scale sum and abs(diff) by 0.5
    private val sumScaled = context.createGain().also { it.gain.value = 0.5f }
    private val absDiffScaled = context.createGain().also { it.gain.value = -0.5f } // Negative for subtraction
    
    // Final result: 0.5*(a+b) - 0.5*|a-b|
    private val resultGain = context.createGain().also { it.gain.value = 1f }
    
    init {
        // Connect splitters to their respective paths
        inputASplitter.connect(inputAToSum)
        inputASplitter.connect(inputAToDiff)
        inputBSplitter.connect(inputBToSum)
        inputBSplitter.connect(inputBToDiff)
        
        // Wire input A to sum and diff
        inputAToSum.connect(sumGain)
        inputAToDiff.connect(diffGain)
        
        // Wire input B to sum and diff (inverted for diff)
        inputBToSum.connect(sumGain)
        inputBToDiff.connect(diffGain)
        
        // Sum path: sum -> scale by 0.5 -> result
        sumGain.connect(sumScaled)
        sumScaled.connect(resultGain)
        
        // Diff path: diff -> abs -> scale by 0.5 -> result (subtracted)
        diffGain.connect(absShaper)
        absShaper.connect(absDiffScaled)
        absDiffScaled.connect(resultGain)
    }
    
    override val inputA: AudioInput = WebAudioNodeInput(inputASplitter, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(inputBSplitter, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(resultGain)
}

actual interface Maximum : AudioUnit {
    actual val inputA: AudioInput
    actual val inputB: AudioInput
}

/**
 * Maximum of two signals using Web Audio nodes.
 * Formula: max(a,b) = 0.5*(a+b) + 0.5*|a-b|
 * 
 * Implementation:
 * 1. Sum = a + b (using sumGain)
 * 2. Diff = a - b (using diffGain with inverted inputB)
 * 3. AbsDiff = |a - b| (using WaveShaperNode with abs curve)
 * 4. Result = 0.5*Sum + 0.5*AbsDiff
 */
class WebAudioMaximum(private val context: AudioContext) : Maximum {
    // Create input splitters to route inputs to both sum and diff paths
    private val inputASplitter = context.createGain().also { it.gain.value = 1f }
    private val inputBSplitter = context.createGain().also { it.gain.value = 1f }
    
    // Input A gain nodes (routed from splitter)
    private val inputAToSum = context.createGain().also { it.gain.value = 1f }
    private val inputAToDiff = context.createGain().also { it.gain.value = 1f }
    
    // Input B gain nodes (routed from splitter)
    private val inputBToSum = context.createGain().also { it.gain.value = 1f }
    private val inputBToDiff = context.createGain().also { it.gain.value = -1f } // Inverted for subtraction
    
    // Sum: a + b
    private val sumGain = context.createGain().also { it.gain.value = 1f }
    
    // Diff: a - b
    private val diffGain = context.createGain().also { it.gain.value = 1f }
    
    // Absolute value of difference using WaveShaper
    private val absShaper = context.createWaveShaper().apply {
        val samples = 1024
        val curve = Float32Array(samples)
        for (i in 0 until samples) {
            val x = (i.toFloat() / (samples - 1)) * 2 - 1  // -1 to 1
            curve[i] = kotlin.math.abs(x)
        }
        this.curve = curve
        this.oversample = "none"
    }
    
    // Scale sum and abs(diff) by 0.5
    private val sumScaled = context.createGain().also { it.gain.value = 0.5f }
    private val absDiffScaled = context.createGain().also { it.gain.value = 0.5f } // Positive for addition
    
    // Final result: 0.5*(a+b) + 0.5*|a-b|
    private val resultGain = context.createGain().also { it.gain.value = 1f }
    
    init {
        // Connect splitters to their respective paths
        inputASplitter.connect(inputAToSum)
        inputASplitter.connect(inputAToDiff)
        inputBSplitter.connect(inputBToSum)
        inputBSplitter.connect(inputBToDiff)
        
        // Wire input A to sum and diff
        inputAToSum.connect(sumGain)
        inputAToDiff.connect(diffGain)
        
        // Wire input B to sum and diff (inverted for diff)
        inputBToSum.connect(sumGain)
        inputBToDiff.connect(diffGain)
        
        // Sum path: sum -> scale by 0.5 -> result
        sumGain.connect(sumScaled)
        sumScaled.connect(resultGain)
        
        // Diff path: diff -> abs -> scale by 0.5 -> result (added)
        diffGain.connect(absShaper)
        absShaper.connect(absDiffScaled)
        absDiffScaled.connect(resultGain)
    }
    
    override val inputA: AudioInput = WebAudioNodeInput(inputASplitter, 0, context)
    override val inputB: AudioInput = WebAudioNodeInput(inputBSplitter, 0, context)
    override val output: AudioOutput = WebAudioNodeOutput(resultGain)
}
