package org.balch.orpheus.plugins.horn

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
import org.balch.orpheus.core.plugin.symbols.HORN_URI
import org.balch.orpheus.core.plugin.symbols.HornSymbol

/**
 * Horn Plugin — Leslie speaker simulation.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class HornPlugin : DspPlugin {

    override val info = PluginInfo(uri = URI, name = "Horn", author = "Orpheus")

    companion object {
        const val URI = HORN_URI
    }

    // Internal state
    private var _speed = 0.5f
    private var _ratio = 0.5f
    private var _depth = 0.5f
    private var _amount = 0.5f
    private var _mix = 0.0f
    private var _brake = false

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(HornSymbol.SPEED) {
            floatType {
                default = 0.5f
                get { _speed }
                set { _speed = it }
            }
        }

        controlPort(HornSymbol.RATIO) {
            floatType {
                default = 0.5f
                get { _ratio }
                set { _ratio = it }
            }
        }

        controlPort(HornSymbol.DEPTH) {
            floatType {
                default = 0.5f
                get { _depth }
                set { _depth = it }
            }
        }

        controlPort(HornSymbol.AMOUNT) {
            floatType {
                default = 0.5f
                get { _amount }
                set { _amount = it }
            }
        }

        controlPort(HornSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it }
            }
        }

        controlPort(HornSymbol.BRAKE) {
            boolType {
                default = false
                get { _brake }
                set { _brake = it }
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
