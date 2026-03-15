package org.balch.orpheus.plugins.resonator

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.RESONATOR_URI
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol

/**
 * Resonator Plugin (Modal synthesis and string).
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps `audioEngine` for native forwarding methods.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class ResonatorPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Resonator",
        author = "Balch"
    )

    companion object {
        const val URI = RESONATOR_URI
    }

    // Internal state
    private var _mode = 0
    private var _targetMix = 0.0f
    private var _structure = 0.25f
    private var _brightness = 0.5f
    private var _damping = 0.3f
    private var _position = 0.5f
    private var _mix = 0.0f
    private var _snapBack = false

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 8) {
        controlPort(ResonatorSymbol.MODE) {
            intType {
                min = 0; max = 2
                options = listOf("Bar", "Sitar", "String")
                get { _mode }
                set { _mode = it }
            }
        }

        controlPort(ResonatorSymbol.TARGET_MIX) {
            floatType {
                default = 0f
                get { _targetMix }
                set { _targetMix = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(ResonatorSymbol.STRUCTURE) {
            floatType {
                default = 0.25f
                get { _structure }
                set { _structure = it }
            }
        }

        controlPort(ResonatorSymbol.BRIGHTNESS) {
            floatType {
                get { _brightness }
                set { _brightness = it }
            }
        }

        controlPort(ResonatorSymbol.DAMPING) {
            floatType {
                default = 0.3f
                get { _damping }
                set { _damping = it }
            }
        }

        controlPort(ResonatorSymbol.POSITION) {
            floatType {
                get { _position }
                set { _position = it }
            }
        }

        controlPort(ResonatorSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it.coerceIn(0f, 1f) }
            }
        }

        controlPort(ResonatorSymbol.SNAP_BACK) {
            boolType {
                get { _snapBack }
                set { _snapBack = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "drum_l"; name = "Drum Left"; isInput = true }
        audioPort { index = 1; symbol = "drum_r"; name = "Drum Right"; isInput = true }
        audioPort { index = 2; symbol = "synth_l"; name = "Synth Left"; isInput = true }
        audioPort { index = 3; symbol = "synth_r"; name = "Synth Right"; isInput = true }
        audioPort { index = 4; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 5; symbol = "out_r"; name = "Output Right"; isInput = false }
        audioPort { index = 6; symbol = "aux_l"; name = "Aux Left"; isInput = false }
        audioPort { index = 7; symbol = "aux_r"; name = "Aux Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Native forwarding methods
    fun setTargetMixGains(drumExcite: Float, synthExcite: Float) {
        audioEngine.setPort(URI, "drum_ex_gain", drumExcite)
        audioEngine.setPort(URI, "synth_ex_gain", synthExcite)
        audioEngine.setPort(URI, "drum_bp_gain", 1f - drumExcite)
        audioEngine.setPort(URI, "synth_bp_gain", 1f - synthExcite)
    }

    fun setMixGains(wet: Float, dry: Float) {
        audioEngine.setPort(URI, "wet_gain", wet)
        audioEngine.setPort(URI, "dry_gain", dry)
    }

    fun strum(frequency: Float) {
        audioEngine.setPort(URI, "strum_freq", frequency)
    }
}
