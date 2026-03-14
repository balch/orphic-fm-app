package org.balch.orpheus.plugins.duolfo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioUnit
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
 * Two Oscillators (A and B) with logical AND/OR combination.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DuoLfoPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Duo LFO",
        author = "Balch"
    )

    companion object {
        const val URI = DUO_LFO_URI
    }

    // Internal State
    private var _mode = 1
    private var _link = false
    private var _shape = 1.0f
    private var _freqA = 0.0f
    private var _freqB = 0.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 6) {
        controlPort(DuoLfoSymbol.MODE) {
            intType {
                default = 1; min = 0; max = 2
                options = listOf("AND", "OFF", "OR")
                get { _mode }
                set { _mode = it }
            }
        }

        controlPort(DuoLfoSymbol.LINK) {
            boolType {
                get { _link }
                set { _link = it }
            }
        }

        controlPort(DuoLfoSymbol.SHAPE) {
            floatType {
                default = 1f; min = 0f; max = 1f
                get { _shape }
                set { _shape = it }
            }
        }

        controlPort(DuoLfoSymbol.FREQ_A) {
            floatType {
                default = 0f
                get { _freqA }
                set { _freqA = it }
            }
        }

        controlPort(DuoLfoSymbol.FREQ_B) {
            floatType {
                default = 0f
                get { _freqB }
                set { _freqB = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "freq_a"; name = "Frequency A"; isInput = true }
        audioPort { index = 1; symbol = "freq_b"; name = "Frequency B"; isInput = true }
        audioPort { index = 2; symbol = "feedback"; name = "Feedback"; isInput = true }
        audioPort { index = 3; symbol = "out"; name = "Output"; isInput = false }
        audioPort { index = 4; symbol = "out_a"; name = "Output A"; isInput = false }
        audioPort { index = 5; symbol = "out_b"; name = "Output B"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = emptyList()

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
