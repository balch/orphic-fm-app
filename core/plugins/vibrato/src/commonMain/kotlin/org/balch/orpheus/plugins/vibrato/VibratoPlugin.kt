package org.balch.orpheus.plugins.vibrato

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
import org.balch.orpheus.core.plugin.symbols.VIBRATO_URI
import org.balch.orpheus.core.plugin.symbols.VibratoSymbol

/**
 * Vibrato Plugin (Global pitch wobble).
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class VibratoPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Vibrato",
        author = "Balch"
    )

    companion object {
        const val URI = VIBRATO_URI
    }

    // Internal state
    private var _depth = 0.0f
    private var _rate = 5.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 1) {
        controlPort(VibratoSymbol.DEPTH) {
            floatType {
                default = 0f
                get { _depth }
                set { _depth = it }
            }
        }

        controlPort(VibratoSymbol.RATE) {
            floatType {
                default = 5.0f; min = 0.1f; max = 20.0f
                get { _rate }
                set { _rate = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "output"; name = "Output"; isInput = false }
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
