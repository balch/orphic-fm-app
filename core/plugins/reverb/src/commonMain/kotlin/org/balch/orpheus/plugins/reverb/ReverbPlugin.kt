package org.balch.orpheus.plugins.reverb

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.REVERB_URI
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol

/**
 * Reverb Plugin — Dattorro plate reverb.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class ReverbPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Reverb",
        author = "Balch"
    )

    companion object {
        const val URI = REVERB_URI
    }

    // Internal state
    private var _amount = 0f
    private var _time = 0.5f
    private var _damping = 0.7f
    private var _diffusion = 0.625f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(ReverbSymbol.AMOUNT) {
            floatType {
                default = 0f
                get { _amount }
                set { _amount = it }
            }
        }

        controlPort(ReverbSymbol.TIME) {
            floatType {
                default = 0.5f
                get { _time }
                set { _time = it }
            }
        }

        controlPort(ReverbSymbol.DAMPING) {
            floatType {
                default = 0.7f
                get { _damping }
                set { _damping = it }
            }
        }

        controlPort(ReverbSymbol.DIFFUSION) {
            floatType {
                default = 0.625f
                get { _diffusion }
                set { _diffusion = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
