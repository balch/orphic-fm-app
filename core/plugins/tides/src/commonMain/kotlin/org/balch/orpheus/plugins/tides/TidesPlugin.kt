package org.balch.orpheus.plugins.tides

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
import org.balch.orpheus.core.plugin.symbols.TIDES_URI
import org.balch.orpheus.core.plugin.symbols.TidesSymbol

/**
 * Tides Polyphonic Function Generator Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps `audioEngine` for native forwarding methods.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class TidesPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Tides",
        author = "Balch"
    )

    companion object {
        const val URI = TIDES_URI
    }

    // Internal state tracking
    private var _frequency = 0.5f
    private var _slope = 0.5f
    private var _shape = 0.5f
    private var _smoothness = 0.5f
    private var _shift = 0.0f
    private var _mix = 0.0f
    private var _clockOffset = 0.0f
    private var _rampMode = 1   // LOOPING
    private var _outputMode = 0 // GATES
    private var _range = 0      // CONTROL
    private var _gateSource = 0 // Voice Gate
    private var _clockSource = 0 // Internal

    // Type-safe DSL port definitions (no audio ports — unit reads/writes engine buffers internally)
    private val portDefs = ports(startIndex = 0) {
        controlPort(TidesSymbol.FREQUENCY) {
            floatType {
                default = 0.5f
                get { _frequency }
                set { _frequency = it }
            }
        }

        controlPort(TidesSymbol.SLOPE) {
            floatType {
                default = 0.5f
                get { _slope }
                set { _slope = it }
            }
        }

        controlPort(TidesSymbol.SHAPE) {
            floatType {
                default = 0.5f
                get { _shape }
                set { _shape = it }
            }
        }

        controlPort(TidesSymbol.SMOOTHNESS) {
            floatType {
                default = 0.5f
                get { _smoothness }
                set { _smoothness = it }
            }
        }

        controlPort(TidesSymbol.SHIFT) {
            floatType {
                default = 0.0f
                get { _shift }
                set { _shift = it }
            }
        }

        controlPort(TidesSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it }
            }
        }

        controlPort(TidesSymbol.CLOCK_OFFSET) {
            floatType {
                default = 0.0f
                get { _clockOffset }
                set { _clockOffset = it }
            }
        }

        controlPort(TidesSymbol.RAMP_MODE) {
            intType {
                default = 1; min = 0; max = 2
                options = listOf("AD", "Looping", "AR")
                get { _rampMode }
                set { _rampMode = it }
            }
        }

        controlPort(TidesSymbol.OUTPUT_MODE) {
            intType {
                min = 0; max = 3
                options = listOf("Gates", "Amplitude", "Phase", "Frequency")
                get { _outputMode }
                set { _outputMode = it }
            }
        }

        controlPort(TidesSymbol.RANGE) {
            intType {
                min = 0; max = 1
                options = listOf("Control", "Audio")
                get { _range }
                set { _range = it }
            }
        }

        controlPort(TidesSymbol.GATE_SOURCE) {
            intType {
                min = 0; max = 4
                options = listOf("Voice", "T1", "T2", "T3", "Free-run")
                get { _gateSource }
                set { _gateSource = it }
            }
        }

        controlPort(TidesSymbol.CLOCK_SOURCE) {
            intType {
                min = 0; max = 1
                options = listOf("Internal", "Master Clock")
                get { _clockSource }
                set { _clockSource = it }
            }
        }
    }

    override val ports: List<Port> = portDefs.controlPorts

    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
