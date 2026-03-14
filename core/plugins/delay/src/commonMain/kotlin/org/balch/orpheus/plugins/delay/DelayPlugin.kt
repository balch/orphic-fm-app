package org.balch.orpheus.plugins.delay

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DELAY_URI
import org.balch.orpheus.core.plugin.symbols.DelaySymbol

/**
 * Dual Delay Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DelayPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Dual Delay",
        author = "Orpheus"
    )

    companion object {
        const val URI = DELAY_URI
    }

    // Internal state
    private var _feedback = 0.5f
    private var _mix = 0f
    private var _time1 = 0.3f
    private var _time2 = 0.3f
    private var _modDepth1 = 0f
    private var _modDepth2 = 0f
    private var _stereoMode = false
    private var _modSourceIsLfo = true
    private var _lfoWaveformIsTriangle = true

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 7) {
        controlPort(DelaySymbol.FEEDBACK) {
            floatType {
                default = 0.5f
                get { _feedback }
                set { _feedback = it }
            }
        }

        controlPort(DelaySymbol.MIX) {
            floatType {
                default = 0f
                get { _mix }
                set { _mix = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(DelaySymbol.TIME_1) {
            floatType {
                default = 0.3f
                get { _time1 }
                set { _time1 = it }
            }
        }

        controlPort(DelaySymbol.TIME_2) {
            floatType {
                default = 0.3f
                get { _time2 }
                set { _time2 = it }
            }
        }

        controlPort(DelaySymbol.MOD_DEPTH_1) {
            floatType {
                default = 0f
                get { _modDepth1 }
                set { _modDepth1 = it }
            }
        }

        controlPort(DelaySymbol.MOD_DEPTH_2) {
            floatType {
                default = 0f
                get { _modDepth2 }
                set { _modDepth2 = it }
            }
        }

        controlPort(DelaySymbol.STEREO_MODE) {
            boolType {
                default = false
                get { _stereoMode }
                set { _stereoMode = it }
            }
        }

        controlPort(DelaySymbol.MOD_SOURCE) {
            boolType {
                default = true
                get { _modSourceIsLfo }
                set { _modSourceIsLfo = it }
            }
        }

        controlPort(DelaySymbol.LFO_WAVEFORM) {
            boolType {
                default = true
                get { _lfoWaveformIsTriangle }
                set { _lfoWaveformIsTriangle = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "lfo_in"; name = "LFO Input"; isInput = true }
        audioPort { index = 3; symbol = "wet_1_l"; name = "Wet 1 Left"; isInput = false }
        audioPort { index = 4; symbol = "wet_1_r"; name = "Wet 1 Right"; isInput = false }
        audioPort { index = 5; symbol = "wet_2_l"; name = "Wet 2 Left"; isInput = false }
        audioPort { index = 6; symbol = "wet_2_r"; name = "Wet 2 Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = emptyList()

    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
