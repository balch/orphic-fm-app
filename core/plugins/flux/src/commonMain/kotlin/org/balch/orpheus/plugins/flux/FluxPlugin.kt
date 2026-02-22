package org.balch.orpheus.plugins.flux

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
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol

/**
 * Flux Generative Sequencer Plugin.
 * Wraps the FluxUnit.
 * 
 * Port Map:
 * 0: Clock Input (Audio)
 * 1: Output Gate (Audio)
 * 2: Output CV (Audio)
 * 3: Output CV X1 (Audio)
 * 4: Output CV X3 (Audio)
 * 5: Output Trig T1 (Audio)
 * 6: Output Trig T2 (Audio)
 * 7: Output Trig T3 (Audio)
 * 
 * Controls (via DSL):
 * - spread, bias, steps, dejavu, length, scale, rate, jitter, probability, pulse_width
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class FluxPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Flux",
        author = "Balch"
    )
    
    companion object {
        const val URI = FLUX_URI
    }

    val flux = dspFactory.createFluxUnit()

    // Internal state tracking
    private var _spread = 0.5f
    private var _bias = 0.5f
    private var _steps = 0.5f
    private var _dejaVu = 0.0f
    private var _length = 8
    private var _scale = 0
    private var _rate = 0.5f
    private var _jitter = 0.0f
    private var _probability = 0.5f
    private var _tModel = 0
    private var _tRange = 1
    private var _pulseWidth = 0.5f
    private var _pulseWidthStd = 0.0f
    private var _controlMode = 0
    private var _voltageRange = 2
    private var _mix = 0.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 8) {
        controlPort(FluxSymbol.SPREAD) {
            floatType {
                default = 0.5f
                get { _spread }
                set { _spread = it; flux.spread.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.BIAS) {
            floatType {
                get { _bias }
                set { _bias = it; flux.bias.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.STEPS) {
            floatType {
                get { _steps }
                set { _steps = it; flux.steps.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.DEJAVU) {
            floatType {
                default = 0f
                get { _dejaVu }
                set { _dejaVu = it; flux.dejaVu.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.LENGTH) {
            intType {
                default = 8; min = 1; max = 16
                get { _length }
                set { _length = it; flux.length.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.SCALE) {
            intType {
                min = 0; max = 5
                options = listOf("Major", "Minor", "Pentatonic", "Phrygian", "Dorian", "Chromatic")
                get { _scale }
                set { _scale = it; flux.setScale(it) }
            }
        }
        
        controlPort(FluxSymbol.RATE) {
            floatType {
                get { _rate }
                set { _rate = it; flux.rate.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.JITTER) {
            floatType {
                default = 0f
                get { _jitter }
                set { _jitter = it; flux.jitter.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.PROBABILITY) {
            floatType {
                get { _probability }
                set { _probability = it; flux.probability.set(it.toDouble()) }
            }
        }
        
        controlPort(FluxSymbol.T_MODEL) {
            intType {
                min = 0; max = 6
                options = listOf("Bernoulli", "Clusters", "Drums", "Ind.Bernoulli", "Divider", "3-State", "Markov")
                get { _tModel }
                set { _tModel = it; flux.setTModel(it) }
            }
        }

        controlPort(FluxSymbol.T_RANGE) {
            intType {
                default = 1; min = 0; max = 2
                options = listOf("0.25x", "1x", "4x")
                get { _tRange }
                set { _tRange = it; flux.setTRange(it) }
            }
        }

        controlPort(FluxSymbol.PULSE_WIDTH) {
            floatType {
                default = 0.5f
                get { _pulseWidth }
                set { _pulseWidth = it; flux.pulseWidth.set(it.toDouble()) }
            }
        }

        controlPort(FluxSymbol.PULSE_WIDTH_STD) {
            floatType {
                default = 0.0f
                get { _pulseWidthStd }
                set { _pulseWidthStd = it; flux.setPulseWidthStd(it) }
            }
        }

        controlPort(FluxSymbol.CONTROL_MODE) {
            intType {
                min = 0; max = 2
                options = listOf("Identical", "Bump", "Tilt")
                get { _controlMode }
                set { _controlMode = it; flux.setControlMode(it) }
            }
        }

        controlPort(FluxSymbol.VOLTAGE_RANGE) {
            intType {
                default = 2; min = 0; max = 2
                options = listOf("Narrow", "Positive", "Full")
                get { _voltageRange }
                set { _voltageRange = it; flux.setVoltageRange(it) }
            }
        }

        controlPort(FluxSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set {
                    _mix = it
                    flux.setMix(it)
                    setPluginEnabled(it > 0.001f, audioEngine)
                }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "clock"; name = "Clock In"; isInput = true }
        audioPort { index = 1; symbol = "out"; name = "Gate"; isInput = false }
        audioPort { index = 2; symbol = "cv"; name = "CV"; isInput = false }
        audioPort { index = 3; symbol = "cv_x1"; name = "CV X1"; isInput = false }
        audioPort { index = 4; symbol = "cv_x3"; name = "CV X3"; isInput = false }
        audioPort { index = 5; symbol = "trig_t1"; name = "Trig T1"; isInput = false }
        audioPort { index = 6; symbol = "trig_t2"; name = "Trig T2"; isInput = false }
        audioPort { index = 7; symbol = "trig_t3"; name = "Trig T3"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = listOf(flux)

    override val inputs: Map<String, AudioInput> = mapOf(
        "clock" to flux.clock,
        "spread" to flux.spread,
        "bias" to flux.bias,
        "steps" to flux.steps,
        "dejaVu" to flux.dejaVu,
        "length" to flux.length,
        "rate" to flux.rate,
        "jitter" to flux.jitter,
        "probability" to flux.probability,
        "pulseWidth" to flux.pulseWidth
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to flux.output,
        "outputX1" to flux.outputX1,
        "outputX3" to flux.outputX3,
        "outputT1" to flux.outputT1,
        "outputT2" to flux.outputT2,
        "outputT3" to flux.outputT3
    )

    override fun initialize() {
        // Initialize default values via port API
        setPortValue("spread", PortValue.FloatValue(0.5f))
        setPortValue("bias", PortValue.FloatValue(0.5f))
        setPortValue("steps", PortValue.FloatValue(0.5f))
        setPortValue("dejavu", PortValue.FloatValue(0.0f))
        setPortValue("length", PortValue.IntValue(8))
        setPortValue("scale", PortValue.IntValue(0))
        setPortValue("rate", PortValue.FloatValue(0.5f))
        setPortValue("jitter", PortValue.FloatValue(0.0f))
        setPortValue("probability", PortValue.FloatValue(0.5f))
        setPortValue("t_model", PortValue.IntValue(0))
        setPortValue("t_range", PortValue.IntValue(1))
        setPortValue("pulse_width", PortValue.FloatValue(0.5f))
        setPortValue("pulse_width_std", PortValue.FloatValue(0.0f))
        setPortValue("control_mode", PortValue.IntValue(0))
        setPortValue("voltage_range", PortValue.IntValue(2))
        setPortValue("mix", PortValue.FloatValue(0.0f))

        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun applyInitialBypassState(audioEngine: AudioEngine) {
        setPluginEnabled(_mix > 0.001f, audioEngine)
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}
    
    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
