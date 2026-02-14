package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.TTS_URI
import org.balch.orpheus.core.plugin.symbols.TtsSymbol

/**
 * TTS Plugin — Wraps a TtsPlayerUnit and dedicated SpeechEffectsUnit
 * for routing synthesized speech through its own effects chain
 * (phaser → feedback delay → reverb) directly to stereo sum,
 * bypassing the main distortion/delay/reverb chain.
 *
 * Controls: PITCH, SPEED, VOLUME, REVERB, PHASER, FEEDBACK
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class TtsPlugin(
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "TTS Player",
        author = "Balch"
    )

    companion object {
        const val URI = TTS_URI
    }

    internal val ttsPlayer = dspFactory.createTtsPlayerUnit()
    internal val speechEffects = dspFactory.createSpeechEffectsUnit()

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
                set { _rate = it; ttsPlayer.setRate(it) }
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
                set { _volume = it; ttsPlayer.setVolume(it * 7f) }
            }
        }

        controlPort(TtsSymbol.REVERB) {
            floatType {
                default = 0f
                get { _reverb }
                set { _reverb = it; speechEffects.setReverbAmount(it) }
            }
        }

        controlPort(TtsSymbol.PHASER) {
            floatType {
                default = 0f
                get { _phaser }
                set { _phaser = it; speechEffects.setPhaserIntensity(it) }
            }
        }

        controlPort(TtsSymbol.FEEDBACK) {
            floatType {
                default = 0f
                get { _feedback }
                set { _feedback = it; speechEffects.setFeedbackAmount(it) }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 1; symbol = "out_r"; name = "Output Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

    override val audioUnits: List<AudioUnit> = listOf(ttsPlayer, speechEffects)

    override val inputs: Map<String, AudioInput> = emptyMap()

    // Outputs come from speechEffects (after the effects chain)
    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to speechEffects.output,
        "outputRight" to speechEffects.outputRight
    )

    override fun initialize() {
        ttsPlayer.setRate(_rate)
        ttsPlayer.setVolume(_volume * 3f)

        // Internal wiring: TTS player → speech effects chain
        ttsPlayer.output.connect(speechEffects.inputLeft)
        ttsPlayer.outputRight.connect(speechEffects.inputRight)
    }

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

    // Direct access for SynthEngine
    fun loadAudio(samples: FloatArray, sampleRate: Int) = ttsPlayer.loadAudio(samples, sampleRate)
    fun play() = ttsPlayer.play()
    fun stopPlayback() = ttsPlayer.stop()
    fun isPlaying(): Boolean = ttsPlayer.isPlaying()
}
