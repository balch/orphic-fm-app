package org.balch.orpheus.plugins.flux

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.FLUX_URI
import org.balch.orpheus.core.plugin.symbols.FluxSymbol

/**
 * Flux Generative Sequencer Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps `audioEngine` for native forwarding methods.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class FluxPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Flux",
        author = "Balch"
    )

    companion object {
        const val URI = FLUX_URI
    }

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
    private var _dejaVuMode = 0
    private var _controlMode = 0
    private var _voltageRange = 2
    private var _mix = 0.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 8) {
        controlPort(FluxSymbol.SPREAD) {
            floatType {
                default = 0.5f
                get { _spread }
                set { _spread = it }
            }
        }

        controlPort(FluxSymbol.BIAS) {
            floatType {
                get { _bias }
                set { _bias = it }
            }
        }

        controlPort(FluxSymbol.STEPS) {
            floatType {
                get { _steps }
                set { _steps = it }
            }
        }

        controlPort(FluxSymbol.DEJAVU) {
            floatType {
                default = 0f
                get { _dejaVu }
                set { _dejaVu = it }
            }
        }

        controlPort(FluxSymbol.LENGTH) {
            intType {
                default = 8; min = 1; max = 16
                get { _length }
                set { _length = it }
            }
        }

        controlPort(FluxSymbol.SCALE) {
            intType {
                min = 0; max = 5
                options = listOf("Major", "Minor", "Pentatonic", "Phrygian", "Dorian", "Chromatic")
                get { _scale }
                set { _scale = it }
            }
        }

        controlPort(FluxSymbol.RATE) {
            floatType {
                get { _rate }
                set { _rate = it }
            }
        }

        controlPort(FluxSymbol.JITTER) {
            floatType {
                default = 0f
                get { _jitter }
                set { _jitter = it }
            }
        }

        controlPort(FluxSymbol.PROBABILITY) {
            floatType {
                get { _probability }
                set { _probability = it }
            }
        }

        controlPort(FluxSymbol.T_MODEL) {
            intType {
                min = 0; max = 6
                options = listOf("Bernoulli", "Clusters", "Drums", "Ind.Bernoulli", "Divider", "3-State", "Markov")
                get { _tModel }
                set { _tModel = it }
            }
        }

        controlPort(FluxSymbol.T_RANGE) {
            intType {
                default = 1; min = 0; max = 2
                options = listOf("0.25x", "1x", "4x")
                get { _tRange }
                set { _tRange = it }
            }
        }

        controlPort(FluxSymbol.PULSE_WIDTH) {
            floatType {
                default = 0.5f
                get { _pulseWidth }
                set { _pulseWidth = it }
            }
        }

        controlPort(FluxSymbol.PULSE_WIDTH_STD) {
            floatType {
                default = 0.0f
                get { _pulseWidthStd }
                set { _pulseWidthStd = it }
            }
        }

        controlPort(FluxSymbol.DEJAVU_MODE) {
            intType {
                min = 0; max = 2
                options = listOf("T+X", "T Only", "X Only")
                get { _dejaVuMode }
                set { _dejaVuMode = it }
            }
        }

        controlPort(FluxSymbol.CONTROL_MODE) {
            intType {
                min = 0; max = 2
                options = listOf("Identical", "Bump", "Tilt")
                get { _controlMode }
                set { _controlMode = it }
            }
        }

        controlPort(FluxSymbol.VOLTAGE_RANGE) {
            intType {
                default = 2; min = 0; max = 2
                options = listOf("Narrow", "Positive", "Full")
                get { _voltageRange }
                set { _voltageRange = it }
            }
        }

        controlPort(FluxSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it }
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


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Native forwarding methods
    fun setQuadTriggerMode(quadIndex: Int, enabled: Boolean) {
        audioEngine.setPort(URI, "quad_trigger_mode_$quadIndex", if (enabled) 1f else 0f)
    }

    fun setDrumTriggerSource(drumIndex: Int, sourceIndex: Int) {
        audioEngine.setPort(URI, "drum_trigger_source_$drumIndex", sourceIndex.toFloat())
    }

    fun setDrumPitchSource(drumIndex: Int, xIndex: Int) {
        audioEngine.setPort(URI, "drum_pitch_source_$drumIndex", xIndex.toFloat())
    }

    fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {
        audioEngine.setPort(URI, "quad_pitch_source_$quadIndex", sourceIndex.toFloat())
    }

    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {
        audioEngine.setPort(URI, "quad_trigger_source_$quadIndex", sourceIndex.toFloat())
    }

    fun setClockSource(sourceIndex: Int) {
        audioEngine.setPort(URI, "clock_source", sourceIndex.toFloat())
    }
}
