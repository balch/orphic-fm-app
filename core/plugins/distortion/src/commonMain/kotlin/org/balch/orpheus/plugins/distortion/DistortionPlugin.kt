package org.balch.orpheus.plugins.distortion

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
import org.balch.orpheus.core.plugin.symbols.DISTORTION_URI
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol

/**
 * Distortion Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DistortionPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Distortion",
        author = "Balch",
        version = "1.0.0"
    )

    companion object {
        const val URI = DISTORTION_URI
    }

    // Internal state
    private var _drive = 0.0f
    private var _mix = 0f
    private var _dryLevel = 1.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(DistortionSymbol.DRIVE) {
            floatType {
                default = 0.0f
                get { _drive }
                set { _drive = it }
            }
        }

        controlPort(DistortionSymbol.MIX) {
            floatType {
                default = 0f
                get { _mix }
                set { _mix = it }
            }
        }

        controlPort(DistortionSymbol.DRY_LEVEL) {
            floatType {
                default = 1.0f
                get { _dryLevel }
                set { _dryLevel = it }
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

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
