package org.balch.orpheus.plugins.duolfo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.lv2.AudioPort
import org.balch.orpheus.core.audio.dsp.lv2.ControlPort
import org.balch.orpheus.core.audio.dsp.lv2.PluginInfo
import org.balch.orpheus.core.audio.dsp.lv2.Port
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.Lv2DspPlugin

/**
 * Shared DuoLFO implementation.
 * Two Oscillators (A & B) with logical AND/OR combination.
 * 
 * Port Map:
 * 0: Frequency A Input (Audio)
 * 1: Frequency B Input (Audio)
 * 2: Feedback Input (Audio)
 * 3: Output (Audio)
 * 4: Output A (Audio)
 * 5: Output B (Audio)
 * 6: Mode (Control Input, 0=AND, 1=OFF, 2=OR)
 * 7: Link (Control Input, bool)
 * 8: Triangle Mode (Control Input, bool)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DuoLfoPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
): Lv2DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.duolfo",
        name = "Duo LFO",
        author = "Balch"
    )

    override val ports: List<Port> = listOf(
        AudioPort(0, "freq_a", "Frequency A", true),
        AudioPort(1, "freq_b", "Frequency B", true),
        AudioPort(2, "feedback", "Feedback", true),
        AudioPort(3, "out", "Output", false),
        AudioPort(4, "out_a", "Output A", false),
        AudioPort(5, "out_b", "Output B", false),
        ControlPort(6, "mode", "Mode", 1f, 0f, 2f),
        ControlPort(7, "link", "Link", 0f, 0f, 1f),
        ControlPort(8, "triangle_mode", "Triangle Mode", 1f, 0f, 1f)
    )

    // Interface Units (Proxies)
    private val inputA = dspFactory.createPassThrough()
    private val inputB = dspFactory.createPassThrough()
    private val outputProxy = dspFactory.createPassThrough()
    private val outputAProxy = dspFactory.createPassThrough()
    private val outputBProxy = dspFactory.createPassThrough()

    // Feedback Modulation
    private val feedbackProxy = dspFactory.createPassThrough()
    private val freqAModMixer = dspFactory.createAdd() // BaseFreqA + Feedback
    private val freqBModMixer = dspFactory.createAdd() // BaseFreqB + Feedback

    // Internal Components - Square
    private val lfoASquare = dspFactory.createSquareOscillator()
    private val lfoBSquare = dspFactory.createSquareOscillator()

    // Internal Components - Triangle
    private val lfoATriangle = dspFactory.createTriangleOscillator()
    private val lfoBTriangle = dspFactory.createTriangleOscillator()

    // Bipolar to Unipolar converters: u = x * 0.5 + 0.5
    private val toUnipolarA = dspFactory.createMultiplyAdd()
    private val toUnipolarB = dspFactory.createMultiplyAdd()

    // Logic units (operate on unipolar 0-1 signals)
    private val logicAnd = dspFactory.createMultiply() // Ua * Ub

    // OR logic: Ua + Ub - Ua*Ub
    private val orProduct = dspFactory.createMultiply()  // Ua * Ub
    private val orSum = dspFactory.createAdd()           // Ua + Ub
    private val orResult = dspFactory.createMultiplyAdd() // orSum + (-1 * orProduct)

    // Unipolar to Bipolar converter: x = u * 2 - 1
    private val toBipolarAnd = dspFactory.createMultiplyAdd()
    private val toBipolarOr = dspFactory.createMultiplyAdd()

    // Triangle AND/OR using Min/Max
    private val triangleMin = dspFactory.createMinimum() // AND = MIN(A, B)
    private val triangleMax = dspFactory.createMaximum() // OR = MAX(A, B)
    private val fmGain = dspFactory.createMultiply()

    // LFO Output Monitoring for visualization
    private val lfoMonitor = dspFactory.createPeakFollower()

    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod
    
    // Backing fields for persistence getters
    private var _hyperLfoFreqA = 0.0f
    private var _hyperLfoFreqB = 0.0f
    private var _hyperLfoMode = 1
    private var _hyperLfoLink = false

    override val audioUnits: List<AudioUnit> = listOf(
        inputA, inputB, outputProxy, outputAProxy, outputBProxy, feedbackProxy,
        freqAModMixer, freqBModMixer,
        lfoASquare, lfoBSquare, lfoATriangle, lfoBTriangle,
        toUnipolarA, toUnipolarB, logicAnd,
        orProduct, orSum, orResult,
        toBipolarAnd, toBipolarOr,
        triangleMin, triangleMax, fmGain, lfoMonitor
    )
    
    // Compatibility Accessors
    val frequencyA: AudioInput get() = inputA.input
    val frequencyB: AudioInput get() = inputB.input
    val feedbackInput: AudioInput get() = feedbackProxy.input
    val output: AudioOutput get() = outputProxy.output
    val outputA: AudioOutput get() = outputAProxy.output
    val outputB: AudioOutput get() = outputBProxy.output

    override val inputs: Map<String, AudioInput> = mapOf(
        "frequencyA" to inputA.input,
        "frequencyB" to inputB.input,
        "feedback" to feedbackProxy.input
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to outputProxy.output
    )

    override fun initialize() {
        // Monitor the LFO output for visualization
        outputProxy.output.connect(lfoMonitor.input)
        lfoMonitor.setHalfLife(0.016) // ~60fps response

        // WIRING
        // Base Frequencies -> Mixers (inputA of each)
        inputA.output.connect(freqAModMixer.inputA)
        inputB.output.connect(freqBModMixer.inputA)

        // Feedback -> Both Mixers (inputB of each)
        feedbackProxy.output.connect(freqAModMixer.inputB)
        feedbackProxy.output.connect(freqBModMixer.inputB)

        // Mixers -> All Oscillators
        freqAModMixer.output.connect(lfoASquare.frequency)
        freqAModMixer.output.connect(lfoATriangle.frequency)
        freqBModMixer.output.connect(lfoBSquare.frequency)
        freqBModMixer.output.connect(lfoBTriangle.frequency)

        // Link: A -> FM -> B (for square only)
        inputA.output.connect(fmGain.inputA)
        fmGain.inputB.set(0.0) // Link OFF
        fmGain.output.connect(lfoBSquare.frequency) // Modulate B Square

        // Bipolar to Unipolar
        lfoASquare.output.connect(toUnipolarA.inputA)
        toUnipolarA.inputB.set(0.5)
        toUnipolarA.inputC.set(0.5)

        lfoBSquare.output.connect(toUnipolarB.inputA)
        toUnipolarB.inputB.set(0.5)
        toUnipolarB.inputC.set(0.5)

        // AND LOGIC: Ua * Ub
        toUnipolarA.output.connect(logicAnd.inputA)
        toUnipolarB.output.connect(logicAnd.inputB)

        // Convert AND result back to bipolar
        logicAnd.output.connect(toBipolarAnd.inputA)
        toBipolarAnd.inputB.set(2.0)
        toBipolarAnd.inputC.set(-1.0)

        // OR LOGIC: Ua + Ub - Ua*Ub
        toUnipolarA.output.connect(orProduct.inputA)
        toUnipolarB.output.connect(orProduct.inputB)
        toUnipolarA.output.connect(orSum.inputA)
        toUnipolarB.output.connect(orSum.inputB)
        orProduct.output.connect(orResult.inputA)
        orResult.inputB.set(-1.0)
        orSum.output.connect(orResult.inputC)

        // Convert OR result back to bipolar
        orResult.output.connect(toBipolarOr.inputA)
        toBipolarOr.inputB.set(2.0)
        toBipolarOr.inputC.set(-1.0)

        // Triangle AND/OR using Min/Max
        lfoATriangle.output.connect(triangleMin.inputA)
        lfoBTriangle.output.connect(triangleMin.inputB)
        lfoATriangle.output.connect(triangleMax.inputA)
        lfoBTriangle.output.connect(triangleMax.inputB)

        // Initial Output: Triangle AND (MIN)
        triangleMin.output.connect(outputProxy.input)
        
        // Initial Separate Outputs
        lfoATriangle.output.connect(outputAProxy.input)
        lfoBTriangle.output.connect(outputBProxy.input)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun setMode(mode: Int) {
        _hyperLfoMode = mode
        when (mode) {
            0 -> { // AND
                isAndMode = true
                updateOutput()
            }
            1 -> { // OFF
                outputProxy.input.disconnectAll()
                outputProxy.input.set(0.0)
            }
            2 -> { // OR
                isAndMode = false
                updateOutput()
            }
        }
    }

    fun setTriangleMode(triangleMode: Boolean) {
        if (isTriangleMode == triangleMode) return
        isTriangleMode = triangleMode
        updateOutput()
    }

    private fun updateOutput() {
        outputProxy.input.disconnectAll()
        outputAProxy.input.disconnectAll()
        outputBProxy.input.disconnectAll()

        if (isTriangleMode) {
            // Raw outputs
            lfoATriangle.output.connect(outputAProxy.input)
            lfoBTriangle.output.connect(outputBProxy.input)

            // Triangle mode
            if (isAndMode) {
                triangleMin.output.connect(outputProxy.input)
            } else {
                triangleMax.output.connect(outputProxy.input)
            }
        } else {
            // Raw outputs (Square)
            lfoASquare.output.connect(outputAProxy.input)
            lfoBSquare.output.connect(outputBProxy.input)

            // Square mode
            if (isAndMode) {
                toBipolarAnd.output.connect(outputProxy.input)
            } else {
                toBipolarOr.output.connect(outputProxy.input)
            }
        }
    }

    fun setLink(enabled: Boolean) {
        _hyperLfoLink = enabled
        fmGain.inputB.set(if (enabled) 10.0 else 0.0)
    }

    fun setFreq(index: Int, frequency: Float) {
        if (index == 0) {
            _hyperLfoFreqA = frequency
        } else {
            _hyperLfoFreqB = frequency
        }
        val freqHz = 0.01 + (frequency * 200.0)
        if (index == 0) {
            inputA.input.set(freqHz)
        } else {
            inputB.input.set(freqHz)
        }
    }

    // Getters for state saving
    fun getFreq(index: Int): Float = if (index == 0) _hyperLfoFreqA else _hyperLfoFreqB
    fun getMode(): Int = _hyperLfoMode
    fun getLink(): Boolean = _hyperLfoLink
    
    fun getCurrentValue(): Float = lfoMonitor.getCurrent().toFloat().coerceIn(-1f, 1f)
}
