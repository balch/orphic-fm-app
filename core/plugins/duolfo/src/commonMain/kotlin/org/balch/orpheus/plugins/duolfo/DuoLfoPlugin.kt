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
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.DUO_LFO_URI
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol

/**
 * Shared DuoLFO implementation.
 * Two Oscillators (A & B) with logical AND/OR combination.
 * Shape parameter (0=square, 1=triangle) crossfades between waveforms.
 *
 * Port Map:
 * 0-5: Audio ports (freq inputs, feedback, outputs)
 *
 * Controls (via DSL):
 * - mode (0=AND, 1=OFF, 2=OR), link, shape, freq_a, freq_b
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DuoLfoPlugin(
    private val audioEngine: AudioEngine,
    dspFactory: DspFactory
): DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Duo LFO",
        author = "Balch"
    )

    companion object {
        const val URI = DUO_LFO_URI
    }

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

    // Shape crossfade using single-parameter lerp: out = sq + (tri - sq) * shape
    // This avoids complementary gain tracking issues (independent smoothing of
    // (1-shape) and shape can briefly sum != 1.0, causing amplitude artifacts).
    // Each path: diffUnit computes (tri - sq), blendUnit computes sq + diff * shape.
    //
    // Individual output A
    private val diffA = dspFactory.createMultiplyAdd()     // sq*(-1) + tri = tri - sq
    private val blendA = dspFactory.createMultiplyAdd()    // diff*shape + sq
    // Individual output B
    private val diffB = dspFactory.createMultiplyAdd()     // sq*(-1) + tri = tri - sq
    private val blendB = dspFactory.createMultiplyAdd()    // diff*shape + sq
    // Combined AND path
    private val diffAnd = dspFactory.createMultiplyAdd()   // sqAnd*(-1) + triAnd
    private val blendAnd = dspFactory.createMultiplyAdd()  // diff*shape + sqAnd
    // Combined OR path
    private val diffOr = dspFactory.createMultiplyAdd()    // sqOr*(-1) + triOr
    private val blendOr = dspFactory.createMultiplyAdd()   // diff*shape + sqOr

    // Internal State
    private var _mode = 1
    private var _link = false
    private var _shape = 1.0f
    private var _freqA = 0.0f
    private var _freqB = 0.0f
    private var isAndMode = true

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 6) {
        controlPort(DuoLfoSymbol.MODE) {
            intType {
                default = 1; min = 0; max = 2
                options = listOf("AND", "OFF", "OR")
                get { _mode }
                set {
                    _mode = it
                    when (it) {
                        0 -> {
                            isAndMode = true; updateOutput()
                        }
                        1 -> {
                            outputProxy.input.disconnectAll(); outputProxy.input.set(0.0)
                        }
                        2 -> {
                            isAndMode = false; updateOutput()
                        }
                    }
                }
            }
        }

        controlPort(DuoLfoSymbol.LINK) {
            boolType {
                get { _link }
                set {
                    _link = it
                    fmGain.inputB.set(if (it) 10.0 else 0.0)
                }
            }
        }

        controlPort(DuoLfoSymbol.SHAPE) {
            floatType {
                default = 1f; min = 0f; max = 1f
                get { _shape }
                set {
                    _shape = it
                    updateBlend(it)
                }
            }
        }

        controlPort(DuoLfoSymbol.FREQ_A) {
            floatType {
                default = 0f
                get { _freqA }
                set {
                    _freqA = it
                    val freqHz = it.toDouble()
                    inputA.input.set(freqHz)
                }
            }
        }

        controlPort(DuoLfoSymbol.FREQ_B) {
            floatType {
                default = 0f
                get { _freqB }
                set {
                    _freqB = it
                    val freqHz = it.toDouble()
                    inputB.input.set(freqHz)
                }
            }
        }
    }

    private val audioPorts = ports {
        audioPort {
            index = 0; symbol = "freq_a"; name = "Frequency A"; isInput = true
        }
        audioPort {
            index = 1; symbol = "freq_b"; name = "Frequency B"; isInput = true
        }
        audioPort {
            index = 2; symbol = "feedback"; name = "Feedback"; isInput = true
        }
        audioPort {
            index = 3; symbol = "out"; name = "Output"; isInput = false
        }
        audioPort {
            index = 4; symbol = "out_a"; name = "Output A"; isInput = false
        }
        audioPort {
            index = 5; symbol = "out_b"; name = "Output B"; isInput = false
        }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = listOf(
        inputA, inputB, outputProxy, outputAProxy, outputBProxy, feedbackProxy,
        freqAModMixer, freqBModMixer,
        lfoASquare, lfoBSquare, lfoATriangle, lfoBTriangle,
        toUnipolarA, toUnipolarB, logicAnd,
        orProduct, orSum, orResult,
        toBipolarAnd, toBipolarOr,
        triangleMin, triangleMax, fmGain,
        diffA, blendA, diffB, blendB,
        diffAnd, blendAnd, diffOr, blendOr
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
        // Set oscillator amplitudes (default is 0)
        lfoASquare.amplitude.set(1.0)
        lfoBSquare.amplitude.set(1.0)
        lfoATriangle.amplitude.set(1.0)
        lfoBTriangle.amplitude.set(1.0)

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

        // --- Shape crossfade wiring (permanent) ---
        // Lerp formula: out = sq + (tri - sq) * shape
        // diff = MultiplyAdd: sq * (-1) + tri   → (tri - sq)
        // blend = MultiplyAdd: diff * shape + sq → sq + (tri-sq)*shape

        // Individual output A
        lfoASquare.output.connect(diffA.inputA)
        diffA.inputB.set(-1.0)
        lfoATriangle.output.connect(diffA.inputC)
        diffA.output.connect(blendA.inputA)
        // blendA.inputB = shape (set via updateBlend)
        lfoASquare.output.connect(blendA.inputC)
        blendA.output.connect(outputAProxy.input)

        // Individual output B
        lfoBSquare.output.connect(diffB.inputA)
        diffB.inputB.set(-1.0)
        lfoBTriangle.output.connect(diffB.inputC)
        diffB.output.connect(blendB.inputA)
        lfoBSquare.output.connect(blendB.inputC)
        blendB.output.connect(outputBProxy.input)

        // Combined AND: sq=toBipolarAnd, tri=triangleMin
        toBipolarAnd.output.connect(diffAnd.inputA)
        diffAnd.inputB.set(-1.0)
        triangleMin.output.connect(diffAnd.inputC)
        diffAnd.output.connect(blendAnd.inputA)
        toBipolarAnd.output.connect(blendAnd.inputC)

        // Combined OR: sq=toBipolarOr, tri=triangleMax
        toBipolarOr.output.connect(diffOr.inputA)
        diffOr.inputB.set(-1.0)
        triangleMax.output.connect(diffOr.inputC)
        diffOr.output.connect(blendOr.inputA)
        toBipolarOr.output.connect(blendOr.inputC)

        // Set initial shape (1.0 → full triangle)
        updateBlend(_shape)

        // Initial combined output: AND mode
        blendAnd.output.connect(outputProxy.input)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    /** Set shape on all 4 blend MultiplyAdd units (inputB = shape). */
    private fun updateBlend(shape: Float) {
        val s = shape.toDouble()
        blendA.inputB.set(s)
        blendB.inputB.set(s)
        blendAnd.inputB.set(s)
        blendOr.inputB.set(s)
    }

    /** Switch combined output between AND and OR blended paths. */
    private fun updateOutput() {
        outputProxy.input.disconnectAll()
        if (isAndMode) {
            blendAnd.output.connect(outputProxy.input)
        } else {
            blendOr.output.connect(outputProxy.input)
        }
    }

    fun getCurrentValue(): Float = outputProxy.getInstantaneousValue().toFloat().coerceIn(-1f, 1f)
    fun getCurrentValueA(): Float = outputAProxy.getInstantaneousValue().toFloat().coerceIn(-1f, 1f)
    fun getCurrentValueB(): Float = outputBProxy.getInstantaneousValue().toFloat().coerceIn(-1f, 1f)
}
