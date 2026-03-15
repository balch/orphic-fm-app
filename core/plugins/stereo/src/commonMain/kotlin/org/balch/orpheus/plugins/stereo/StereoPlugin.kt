package org.balch.orpheus.plugins.stereo

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
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.StereoSymbol

/**
 * Stereo Plugin (Output stage).
 *
 * Pure state container — C++ handles all audio processing.
 * Keeps `audioEngine` for `setQuadVolume()` and `setVoicePan()` forwarding.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class StereoPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Stereo Output",
        author = "Balch"
    )

    companion object {
        const val URI = STEREO_URI
    }

    // Internal state
    private val _voicePan = FloatArray(12) { 0f }
    private var _masterPan = 0f
    private var _masterVolume = 0.7f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 5) {
        controlPort(StereoSymbol.MASTER_PAN) {
            floatType {
                default = 0f; min = -1f; max = 1f
                get { _masterPan }
                set { _masterPan = it.coerceIn(-1f, 1f) }
            }
        }

        controlPort(StereoSymbol.MASTER_VOL) {
            floatType {
                default = 0.7f
                excludeFromPresets = true
                get { _masterVolume }
                set { _masterVolume = it }
            }
        }

        // Voice pans 0-11
        for (i in 0 until 12) {
            controlPort(StereoSymbol.entries[i + 2]) { // Skip MASTER_PAN and MASTER_VOL
                floatType {
                    default = when(i) {
                        2 -> -0.3f; 3 -> -0.3f; 4 -> 0.3f; 5 -> 0.3f
                        6 -> -0.7f; 7 -> 0.7f
                        else -> 0f
                    }
                    min = -1f; max = 1f
                    get { _voicePan[i] }
                    set { _voicePan[i] = it.coerceIn(-1f, 1f) }
                }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Left Input"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Right Input"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Left Output"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Right Output"; isInput = false }
        audioPort { index = 4; symbol = "peak"; name = "Peak Monitor"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


    override fun onStart() {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    fun setQuadVolume(quadIndex: Int, volume: Float) {
        audioEngine.setPort(URI, "quad_vol_$quadIndex", volume)
    }

    fun setVoicePan(voiceIndex: Int, leftGain: Float, rightGain: Float) {
        audioEngine.setPort(URI, "voice_pan_L_$voiceIndex", leftGain)
        audioEngine.setPort(URI, "voice_pan_R_$voiceIndex", rightGain)
    }
}
