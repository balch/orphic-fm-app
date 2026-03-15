package org.balch.orpheus.plugins.warps

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
import org.balch.orpheus.core.plugin.symbols.WARPS_URI
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol

/**
 * Warps Meta-Modulator Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class WarpsPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Warps",
        author = "Balch"
    )

    companion object {
        const val URI = WARPS_URI
    }

    // Internal state
    private var _algorithm = 0f
    private var _timbre = 0.5f
    private var _level1 = 0.5f
    private var _level2 = 0.5f
    private var _mix = 0f
    private var _carrierSource = 0 // WarpsSource.SYNTH
    private var _modulatorSource = 1 // WarpsSource.DRUMS

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 4) {
        controlPort(WarpsSymbol.ALGORITHM) {
            floatType {
                default = 0f; min = 0f; max = 8f
                get { _algorithm }
                set { _algorithm = it }
            }
        }

        controlPort(WarpsSymbol.TIMBRE) {
            floatType {
                get { _timbre }
                set { _timbre = it }
            }
        }

        controlPort(WarpsSymbol.LEVEL1) {
            floatType {
                get { _level1 }
                set { _level1 = it }
            }
        }

        controlPort(WarpsSymbol.LEVEL2) {
            floatType {
                get { _level2 }
                set { _level2 = it }
            }
        }

        controlPort(WarpsSymbol.MIX) {
            floatType {
                default = 0f
                get { _mix }
                set { _mix = it }
            }
        }

        controlPort(WarpsSymbol.CARRIER_SOURCE) {
            intType {
                default = 0; min = 0; max = 8
                get { _carrierSource }
                set { _carrierSource = it }
            }
        }

        controlPort(WarpsSymbol.MODULATOR_SOURCE) {
            intType {
                default = 0; min = 0; max = 8
                get { _modulatorSource }
                set { _modulatorSource = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "carrier"; name = "Carrier"; isInput = true }
        audioPort { index = 1; symbol = "modulator"; name = "Modulator"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Routing state accessors
    fun setCarrierSource(source: Int) { _carrierSource = source }
    fun getCarrierSource(): Int = _carrierSource
    fun setModulatorSource(source: Int) { _modulatorSource = source }
    fun getModulatorSource(): Int = _modulatorSource
}
