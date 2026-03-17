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
import org.balch.orpheus.core.plugin.symbols.POLY_LFO_URI
import org.balch.orpheus.core.plugin.symbols.PolyLfoSymbol

/**
 * PolyLFO plugin — 4-channel poly LFO with shape morphing, spread, and coupling.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class PolyLfoPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "PolyLFO",
        author = "Balch"
    )

    companion object {
        const val URI = POLY_LFO_URI
    }

    // Internal State
    private var _shape = 0.0f
    private var _shapeSpread = 0.5f
    private var _spread = 0.5f
    private var _coupling = 0.5f
    private var _rate = 0.5f
    private var _bypass = true

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 0) {
        controlPort(PolyLfoSymbol.SHAPE) {
            floatType {
                default = 0f; min = 0f; max = 1f
                get { _shape }
                set { _shape = it }
            }
        }

        controlPort(PolyLfoSymbol.SHAPE_SPREAD) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _shapeSpread }
                set { _shapeSpread = it }
            }
        }

        controlPort(PolyLfoSymbol.SPREAD) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _spread }
                set { _spread = it }
            }
        }

        controlPort(PolyLfoSymbol.COUPLING) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _coupling }
                set { _coupling = it }
            }
        }

        controlPort(PolyLfoSymbol.RATE) {
            floatType {
                default = 0.5f; min = 0f; max = 1f
                get { _rate }
                set { _rate = it }
            }
        }

        controlPort(PolyLfoSymbol.BYPASS) {
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
