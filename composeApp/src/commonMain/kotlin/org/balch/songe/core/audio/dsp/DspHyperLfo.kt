package org.balch.songe.core.audio.dsp

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
class DspHyperLfo(private val audioEngine: AudioEngine) {
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

    // Internal State
    private var isAndMode = true
    private var isTriangleMode = true // Default to triangle for delay mod

    init {
        // Add Units to engine
        audioEngine.addUnit(inputA)
        audioEngine.addUnit(inputB)
        audioEngine.addUnit(outputProxy)
        audioEngine.addUnit(feedbackProxy)
        audioEngine.addUnit(freqAModMixer)
        audioEngine.addUnit(freqBModMixer)
        audioEngine.addUnit(lfoASquare)
        audioEngine.addUnit(lfoBSquare)
        audioEngine.addUnit(lfoATriangle)
        audioEngine.addUnit(lfoBTriangle)
        audioEngine.addUnit(toUnipolarA)
        audioEngine.addUnit(toUnipolarB)
        audioEngine.addUnit(logicAnd)
        audioEngine.addUnit(orProduct)
        audioEngine.addUnit(orSum)
        audioEngine.addUnit(orResult)
        audioEngine.addUnit(toBipolarAnd)
        audioEngine.addUnit(toBipolarOr)
        audioEngine.addUnit(triangleMin)
        audioEngine.addUnit(triangleMax)
        audioEngine.addUnit(fmGain)

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
        when (mode) {
            0 -> { // AND
                isAndMode = true
                updateOutput()
            }

            1 -> { // OFF - mute output
                outputProxy.input.disconnectAll()
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
        // Simple FM depth switch
        fmGain.inputB.set(if (enabled) 10.0 else 0.0)
    }
}
