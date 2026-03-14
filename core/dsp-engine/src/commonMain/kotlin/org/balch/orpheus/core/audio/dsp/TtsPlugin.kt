package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.Symbol
import org.balch.orpheus.core.plugin.ports
import org.balch.orpheus.core.plugin.symbols.TTS_URI
import org.balch.orpheus.core.plugin.symbols.TtsSymbol

/**
 * TTS Plugin — Routes synthesized speech through C++ audio engine.
 *
 * Pure state container — C++ handles all audio processing.
 * Always uses native path via NativeDspBridge.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class TtsPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "TTS Player",
        author = "Balch"
    )

    companion object {
        const val URI = TTS_URI
    }

    private var _rate = 0.5f
    private var _speed = 0.5f
    private var _volume = 0.5f
    private var _reverb = 0f
    private var _phaser = 0f
    private var _feedback = 0f

    private val portDefs = ports(startIndex = 2) {
        controlPort(TtsSymbol.RATE) {
            floatType {
                default = 0.5f
                min = 0.25f
                max = 2f
                get { _rate }
                set { _rate = it }
            }
        }

        controlPort(TtsSymbol.SPEED) {
            floatType {
                default = 0.5f
                get { _speed }
                set { _speed = it }
            }
        }

        controlPort(TtsSymbol.VOLUME) {
            floatType {
                default = 0.5f
                get { _volume }
                set { _volume = it }
            }
        }

        controlPort(TtsSymbol.REVERB) {
            floatType {
                default = 0f
                get { _reverb }
                set { _reverb = it }
            }
        }

        controlPort(TtsSymbol.PHASER) {
            floatType {
                default = 0f
                get { _phaser }
                set { _phaser = it }
            }
        }

        controlPort(TtsSymbol.FEEDBACK) {
            floatType {
                default = 0f
                get { _feedback }
                set { _feedback = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = emptyList()

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    /** Speed normalized 0-1, mapped to WPM for TTS generation */
    fun getSpeedWpm(): Int {
        // 0.0 = 80 WPM (slow), 1.0 = 300 WPM (fast)
        return (80 + _speed * 220).toInt()
    }

    // Direct access for SynthEngine — always native path
    fun loadAudio(samples: FloatArray, sampleRate: Int) {
        (audioEngine as NativeDspBridge).nativeLoadTtsAudio(samples, sampleRate)
    }

    fun play() {
        (audioEngine as NativeDspBridge).nativePlayTts()
    }

    fun stopPlayback() {
        (audioEngine as NativeDspBridge).nativeStopTts()
    }

    fun isPlaying(): Boolean {
        return (audioEngine as NativeDspBridge).nativeIsTtsPlaying() != 0
    }
}
