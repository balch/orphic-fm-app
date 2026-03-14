package org.balch.orpheus.plugins.looper

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.plugin.PluginInfo
import org.balch.orpheus.core.plugin.Port
import org.balch.orpheus.core.plugin.ports

/**
 * DSP Plugin for Native Audio Looper.
 *
 * Pure state container — C++ handles all audio processing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class LooperPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Looper",
        author = "Balch"
    )

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
        audioPort { index = 4; symbol = "record_gate"; name = "Record Gate"; isInput = true }
        audioPort { index = 5; symbol = "play_gate"; name = "Play Gate"; isInput = true }
    }

    override val ports: List<Port> = audioPorts.ports

    override val audioUnits: List<AudioUnit> = emptyList()

    override fun initialize() {
        audioEngine.setPort(URI, "level", DEFAULT_LEVEL * LEVEL_SCALE)
    }

    companion object {
        const val URI = "org.balch.orpheus.plugins.looper"
        const val DEFAULT_LEVEL = 0.7f
        private const val LEVEL_SCALE = 2f
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun clear() {
        audioEngine.setPort(URI, "state", 0f)
    }

    fun setRecording(active: Boolean) {
        audioEngine.setPort(URI, "state", if (active) 1f else 2f)
    }

    fun setPlaying(active: Boolean) {
        audioEngine.setPort(URI, "state", if (active) 2f else 0f)
    }

    fun setQuantize(enabled: Boolean) {
        audioEngine.setPort(URI, "quantize", if (enabled) 1f else 0f)
    }

    fun setLevel(level: Float) {
        audioEngine.setPort(URI, "level", level * LEVEL_SCALE)
    }
}
