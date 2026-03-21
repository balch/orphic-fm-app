package org.balch.orpheus.plugins.bass

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
import org.balch.orpheus.core.plugin.symbols.BASS_URI
import org.balch.orpheus.core.plugin.symbols.BassSymbol

/**
 * Bass Voice Plugin.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class BassPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Bass Voice",
        author = "Balch"
    )

    companion object {
        const val URI = BASS_URI
    }

    // Internal state
    private var _engine = 0          // BassEngine.VCF_ACID.ordinal
    private var _rootNote = 36
    private var _scale = 1           // BassScale.MINOR_PENTATONIC.ordinal
    private var _clockDiv = 2        // ClockDivision.X1.ordinal
    private var _stepCount = 16
    private var _mutation = 0.0f
    private var _cutoff = 0.5f
    private var _resonance = 0.0f
    private var _envelope = 0.7f
    private var _overdrive = 0.0f
    private var _compressor = 0.0f
    private var _mix = 0.0f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 2) {
        controlPort(BassSymbol.ENGINE) {
            intType {
                default = 0; min = 0; max = 3
                options = listOf("VCF Acid", "Phase Dist", "FM", "Bass Drum")
                get { _engine }
                set { _engine = it }
            }
        }

        controlPort(BassSymbol.ROOT_NOTE) {
            intType {
                default = 36; min = 0; max = 127
                get { _rootNote }
                set { _rootNote = it }
            }
        }

        controlPort(BassSymbol.SCALE) {
            intType {
                default = 1; min = 0; max = 5
                options = listOf("Chromatic", "Minor Pent", "Minor", "Major", "Dorian", "Whole Tone")
                get { _scale }
                set { _scale = it }
            }
        }

        controlPort(BassSymbol.CLOCK_DIV) {
            intType {
                default = 2; min = 0; max = 4
                options = listOf("1/4", "1/2", "1x", "2x", "4x")
                get { _clockDiv }
                set { _clockDiv = it }
            }
        }

        controlPort(BassSymbol.STEP_COUNT) {
            intType {
                default = 16; min = 1; max = 16
                get { _stepCount }
                set { _stepCount = it }
            }
        }

        controlPort(BassSymbol.MUTATION) {
            floatType {
                default = 0.0f
                get { _mutation }
                set { _mutation = it }
            }
        }

        controlPort(BassSymbol.CUTOFF) {
            floatType {
                default = 0.5f
                get { _cutoff }
                set { _cutoff = it }
            }
        }

        controlPort(BassSymbol.RESONANCE) {
            floatType {
                default = 0.0f
                get { _resonance }
                set { _resonance = it }
            }
        }

        controlPort(BassSymbol.ENVELOPE) {
            floatType {
                default = 0.7f
                get { _envelope }
                set { _envelope = it }
            }
        }

        controlPort(BassSymbol.OVERDRIVE) {
            floatType {
                default = 0.0f
                get { _overdrive }
                set { _overdrive = it }
            }
        }

        controlPort(BassSymbol.COMPRESSOR) {
            floatType {
                default = 0.0f
                get { _compressor }
                set { _compressor = it }
            }
        }

        controlPort(BassSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
