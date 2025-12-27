package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * Shared HyperLFO implementation using DSP primitive interfaces.
 * Two Oscillators (A & B) with logical AND/OR combination.
 * Supports both Square and Triangle waveforms with AND/OR modes.
 *
 * For square waves (-1 to +1), proper AND/OR logic requires:
 * 1. Convert bipolar (-1,+1) to unipolar (0,1): u = (x + 1) / 2 = x * 0.5 + 0.5
 * 2. Apply logic: AND = Ua * Ub, OR = Ua + Ub - Ua*Ub
 * 3. Convert back to bipolar: x = u * 2 - 1
 *
 * For triangle waves, AND/OR is implemented as:
 * - AND = MIN(A, B) - output follows the lower of the two
 * - OR = MAX(A, B) - output follows the higher of the two
 */
// Rename to DspHyperLfo
@Inject
@ContributesIntoSet(AppScope::class)
class DspHyperLfoPlugin(
    private val audioEngine: AudioEngine
): DspPlugin {
    // Interface Units (Proxies)
    private val inputA = audioEngine.createPassThrough()
    private val inputB = audioEngine.createPassThrough()
    private val outputProxy = audioEngine.createPassThrough()

    // Feedback Modulation
    private val feedbackProxy = audioEngine.createPassThrough()
    private val freqAModMixer = audioEngine.createAdd() // BaseFreqA + Feedback
    private val freqBModMixer = audioEngine.createAdd() // BaseFreqB + Feedback

    // Public Ports (for external connection)
    val frequencyA: AudioInput get() = inputA.input
    val frequencyB: AudioInput get() = inputB.input
    val feedbackInput: AudioInput get() = feedbackProxy.input // TOTAL FB input
    val output: AudioOutput get() = outputProxy.output

    // Internal Components - Square
    private val lfoASquare = audioEngine.createSquareOscillator()
    private val lfoBSquare = audioEngine.createSquareOscillator()

    // Internal Components - Triangle
    private val lfoATriangle = audioEngine.createTriangleOscillator()
    private val lfoBTriangle = audioEngine.createTriangleOscillator()

    // Bipolar to Unipolar converters: u = x * 0.5 + 0.5
    private val toUnipolarA = audioEngine.createMultiplyAdd()
    private val toUnipolarB = audioEngine.createMultiplyAdd()

    // Logic units (operate on unipolar 0-1 signals)
    private val logicAnd = audioEngine.createMultiply() // Ua * Ub

    // OR logic: Ua + Ub - Ua*Ub
    private val orProduct = audioEngine.createMultiply()  // Ua * Ub
    private val orSum = audioEngine.createAdd()           // Ua + Ub
    private val orResult =
        audioEngine.createMultiplyAdd() // orSum + (-1 * orProduct) = Ua + Ub - Ua*Ub

    // Unipolar to Bipolar converter: x = u * 2 - 1
    private val toBipolarAnd = audioEngine.createMultiplyAdd()
    private val toBipolarOr = audioEngine.createMultiplyAdd()

    // Triangle AND/OR using Min/Max
    private val triangleMin = audioEngine.createMinimum() // AND = MIN(A, B)
    private val triangleMax = audioEngine.createMaximum() // OR = MAX(A, B)
    private val fmGain = audioEngine.createMultiply()

    // LFO Output Monitoring for visualization
    private val lfoMonitor = audioEngine.createPeakFollower()

    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod
    
    private var _hyperLfoFreqA = 0.0f
    private var _hyperLfoFreqB = 0.0f
    private var _hyperLfoMode = 1
    private var _hyperLfoLink = false

    override val audioUnits: List<AudioUnit> = listOf(
        inputA, inputB, outputProxy, feedbackProxy,
        freqAModMixer, freqBModMixer,
        lfoASquare, lfoBSquare, lfoATriangle, lfoBTriangle,
        toUnipolarA, toUnipolarB, logicAnd,
        orProduct, orSum, orResult,
        toBipolarAnd, toBipolarOr,
        triangleMin, triangleMax, fmGain, lfoMonitor
    )

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "frequencyA" to inputA.input,
            "frequencyB" to inputB.input,
            "feedback" to feedbackProxy.input
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
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

        // ═══════════════════════════════════════════════════════════
        // BIPOLAR TO UNIPOLAR CONVERSION
        // u = x * 0.5 + 0.5 (converts -1,+1 to 0,1)
        // ═══════════════════════════════════════════════════════════
        lfoASquare.output.connect(toUnipolarA.inputA)
        toUnipolarA.inputB.set(0.5)
        toUnipolarA.inputC.set(0.5)

        lfoBSquare.output.connect(toUnipolarB.inputA)
        toUnipolarB.inputB.set(0.5)
        toUnipolarB.inputC.set(0.5)

        // ═══════════════════════════════════════════════════════════
        // AND LOGIC: Ua * Ub (works correctly for 0/1 values)
        // ═══════════════════════════════════════════════════════════
        toUnipolarA.output.connect(logicAnd.inputA)
        toUnipolarB.output.connect(logicAnd.inputB)

        // Convert AND result back to bipolar: x = u * 2 - 1
        logicAnd.output.connect(toBipolarAnd.inputA)
        toBipolarAnd.inputB.set(2.0)
        toBipolarAnd.inputC.set(-1.0)

        // ═══════════════════════════════════════════════════════════
        // OR LOGIC: Ua + Ub - Ua*Ub
        // ═══════════════════════════════════════════════════════════
        // First: Ua * Ub
        toUnipolarA.output.connect(orProduct.inputA)
        toUnipolarB.output.connect(orProduct.inputB)

        // Second: Ua + Ub
        toUnipolarA.output.connect(orSum.inputA)
        toUnipolarB.output.connect(orSum.inputB)

        // Third: (Ua + Ub) + (-1 * Ua*Ub) = Ua + Ub - Ua*Ub
        orProduct.output.connect(orResult.inputA)
        orResult.inputB.set(-1.0)
        orSum.output.connect(orResult.inputC)

        // Convert OR result back to bipolar: x = u * 2 - 1
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
    }

    /**
     * Set LFO mode: 0=AND, 1=OFF, 2=OR
     */
    fun setMode(mode: Int) {
        _hyperLfoMode = mode
        when (mode) {
            0 -> { // AND
                isAndMode = true
                updateOutput()
            }

            1 -> { // OFF - output stable 0 (becomes 0.5 after unipolar conversion)
                outputProxy.input.disconnectAll()
                outputProxy.input.set(0.0) // Stable center position
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

        if (isTriangleMode) {
            // Triangle mode: AND = MIN, OR = MAX
            if (isAndMode) {
                triangleMin.output.connect(outputProxy.input)
            } else {
                triangleMax.output.connect(outputProxy.input)
            }
        } else {
            // Square mode: use AND/OR logic (with proper bipolar conversion)
            if (isAndMode) {
                toBipolarAnd.output.connect(outputProxy.input)
            } else {
                toBipolarOr.output.connect(outputProxy.input)
            }
        }
    }

    fun setLink(enabled: Boolean) {
        _hyperLfoLink = enabled
        // Simple FM depth switch
        fmGain.inputB.set(if (enabled) 10.0 else 0.0)
    }

    fun setFreq(index: Int, frequency: Float) {
        if (index == 0) {
            _hyperLfoFreqA = frequency
        } else {
            _hyperLfoFreqB = frequency
        }
        val freqHz = 0.01 + (frequency * 10.0)
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

    /**
     * Get the current LFO output value (-1 to 1 range) for visualization.
     */
    fun getCurrentValue(): Float = lfoMonitor.getCurrent().toFloat().coerceIn(-1f, 1f)
}