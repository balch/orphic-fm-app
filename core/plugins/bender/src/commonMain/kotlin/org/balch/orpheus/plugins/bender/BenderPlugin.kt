package org.balch.orpheus.plugins.bender

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
import org.balch.orpheus.core.plugin.symbols.BENDER_URI
import org.balch.orpheus.core.plugin.symbols.BenderSymbol

/**
 * Bender Plugin (Pitch and timbre bending with spring/tension effects).
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class BenderPlugin : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Bender",
        author = "Balch"
    )

    companion object {
        const val URI = BENDER_URI
    }

    // Internal state
    private var _bendAmount = 0.0f
    private var _maxBendSemitones = 24.0f
    private var _randomDepth = 0.1f
    private var _timbreModulation = 0.3f
    private var _springVolume = 0.4f
    private var _tensionVolume = 0.015f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 3) {
        controlPort(BenderSymbol.BEND) {
            floatType {
                default = 0f; min = -1f; max = 1f
                get { _bendAmount }
                set { _bendAmount = it.coerceIn(-1f, 1f) }
            }
        }

        controlPort(BenderSymbol.MAX_BEND) {
            floatType {
                default = 24f; min = 1f; max = 48f
                get { _maxBendSemitones }
                set { _maxBendSemitones = it }
            }
        }

        controlPort(BenderSymbol.RANDOM_DEPTH) {
            floatType {
                default = 0.1f
                get { _randomDepth }
                set { _randomDepth = it }
            }
        }

        controlPort(BenderSymbol.TIMBRE_MOD) {
            floatType {
                default = 0.3f
                get { _timbreModulation }
                set { _timbreModulation = it }
            }
        }

        controlPort(BenderSymbol.SPRING_VOL) {
            floatType {
                default = 0.4f
                get { _springVolume }
                set { _springVolume = it }
            }
        }

        controlPort(BenderSymbol.TENSION_VOL) {
            floatType {
                default = 0.015f; min = 0f; max = 0.1f
                get { _tensionVolume }
                set { _tensionVolume = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "pitch_out"; name = "Pitch Output"; isInput = false }
        audioPort { index = 1; symbol = "timbre_out"; name = "Timbre Output"; isInput = false }
        audioPort { index = 2; symbol = "audio_out"; name = "Audio Output"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
