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
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.symbols.DUO_LFO_URI
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol

/**
 * Shared DuoLFO implementation.
 * Two Oscillators (A & B) with logical AND/OR combination.
 * 
 * Port Map:
 * 0-5: Audio ports (freq inputs, feedback, outputs)
 * 
 * Controls (via DSL):
 * - mode (0=AND, 1=OFF, 2=OR), link, triangle_mode, freq_a, freq_b
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

    // LFO Output Monitoring for visualization
    private val lfoMonitor = dspFactory.createPeakFollower()

    // Internal State
    private var _mode = 1
    private var _link = false
    private var _triangleMode = true
    private var _freqA = 0.0f
    private var _freqB = 0.0f
    private var isAndMode = true
    private var isTriangleMode = true

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

        controlPort(DuoLfoSymbol.TRIANGLE_MODE) {
            boolType {
                default = true
                get { _triangleMode }
                set {
                    if (isTriangleMode != it) {
                        isTriangleMode = it
                        _triangleMode = it
                        updateOutput()
                    }
                }
            }
        }

        controlPort(DuoLfoSymbol.FREQ_A) {
            floatType {
                default = 0f
                get { _freqA }
                set {
                    _freqA = it
                    val freqHz = 0.01 + it
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
                    val freqHz = 0.01 + it
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

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

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


    
    fun getCurrentValue(): Float = lfoMonitor.getCurrent().toFloat().coerceIn(-1f, 1f)
}
