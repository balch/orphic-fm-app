package org.balch.orpheus.plugins.duolfo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.LORENZ_URI
import org.balch.orpheus.core.plugin.symbols.LorenzSymbol

/**
 * Lorenz attractor plugin — chaotic modulation source.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class LorenzPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Lorenz",
        author = "Balch"
    )

    companion object {
        const val URI = LORENZ_URI
    }

    // Internal State
    private var _rate = 0.5f
    private var _balance = 0.5f
    private var _bypass = true

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 0) {
        controlPort(LorenzSymbol.RATE) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _rate }
                set { _rate = it }
            }
        }

        controlPort(LorenzSymbol.BALANCE) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _balance }
                set { _balance = it }
            }
        }

        controlPort(LorenzSymbol.BYPASS) {
            boolType {
                default = true
                get { _bypass }
                set { _bypass = it }
            }
        }
    }

    override val ports: List<Port> = portDefs.controlPorts

    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
