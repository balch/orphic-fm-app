package org.balch.orpheus.plugins.grains

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
import org.balch.orpheus.core.plugin.symbols.GRAINS_URI
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol

/**
 * Grains Texture Synthesizer Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class GrainsPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Grains",
        author = "Balch"
    )

    companion object {
        const val URI = GRAINS_URI
    }

    // Internal state
    private var _position = 0.2f
    private var _size = 0.5f
    private var _pitch = 0.0f
    private var _density = 0.5f
    private var _texture = 0.5f
    private var _dryWet = 0f
    private var _freeze = false
    private var _trigger = false
    private var _mode = 0
    private var _feedback = 0f
    private var _reverb = 0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(GrainsSymbol.POSITION) {
            floatType {
                default = 0.2f
                get { _position }
                set { _position = it }
            }
        }

        controlPort(GrainsSymbol.SIZE) {
            floatType {
                get { _size }
                set { _size = it }
            }
        }

        controlPort(GrainsSymbol.PITCH) {
            floatType {
                default = 0.0f; min = -1f; max = 1f
                get { _pitch }
                set { _pitch = it }
            }
        }

        controlPort(GrainsSymbol.DENSITY) {
            floatType {
                get { _density }
                set { _density = it }
            }
        }

        controlPort(GrainsSymbol.TEXTURE) {
            floatType {
                get { _texture }
                set { _texture = it }
            }
        }

        controlPort(GrainsSymbol.DRY_WET) {
            floatType {
                default = 0f
                get { _dryWet }
                set { _dryWet = it }
            }
        }

        controlPort(GrainsSymbol.FREEZE) {
            boolType {
                get { _freeze }
                set { _freeze = it }
            }
        }

        controlPort(GrainsSymbol.TRIGGER) {
            boolType {
                get { _trigger }
                set { _trigger = it }
            }
        }

        controlPort(GrainsSymbol.MODE) {
            intType {
                min = 0; max = 3
                options = listOf("Granular", "Stretch", "Loop", "Spectral")
                get { _mode }
                set { _mode = it }
            }
        }

        controlPort(GrainsSymbol.FEEDBACK) {
            floatType {
                default = 0f
                get { _feedback }
                set { _feedback = it }
            }
        }

        controlPort(GrainsSymbol.REVERB) {
            floatType {
                default = 0f
                get { _reverb }
                set { _reverb = it }
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
