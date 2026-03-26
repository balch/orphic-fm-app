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
    private var _lfoMix = 0.0f
    private var _triggerSource: Int = 0
    private var _pitchSource: Int = 0
    private var _timbreSource: Int = 0
    private var _accentAmount: Float = 0.5f
    private var _jitter: Float = 0.0f
    private var _fxSend: Float = 0.0f

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
                default = 0; min = 0; max = 4
                options = listOf("1x", "2x", "4x", "8x", "16x")
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

        controlPort(BassSymbol.LFO_MIX) {
            floatType {
                default = 0.0f
                get { _lfoMix }
                set { _lfoMix = it }
            }
        }

        controlPort(BassSymbol.TRIGGER_SOURCE) {
            intType {
                default = 0; min = 0; max = 3
                options = listOf("Off", "T1", "T2", "T3")
                get { _triggerSource }
                set { _triggerSource = it }
            }
        }

        controlPort(BassSymbol.PITCH_SOURCE) {
            intType {
                default = 0; min = 0; max = 3
                options = listOf("Off", "X1", "X2", "X3")
                get { _pitchSource }
                set { _pitchSource = it }
            }
        }

        controlPort(BassSymbol.TIMBRE_SOURCE) {
            intType {
                default = 0; min = 0; max = 1
                options = listOf("Off", "Y")
                get { _timbreSource }
                set { _timbreSource = it }
            }
        }

        controlPort(BassSymbol.ACCENT_AMOUNT) {
            floatType {
                default = 0.5f; min = 0.0f; max = 1.0f
                get { _accentAmount }
                set { _accentAmount = it }
            }
        }

        controlPort(BassSymbol.JITTER) {
            floatType {
                default = 0.0f; min = 0.0f; max = 1.0f
                get { _jitter }
                set { _jitter = it }
            }
        }

        controlPort(BassSymbol.FX_SEND) {
            floatType {
                default = 0.0f; min = 0.0f; max = 1.0f
                get { _fxSend }
                set { _fxSend = it }
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
