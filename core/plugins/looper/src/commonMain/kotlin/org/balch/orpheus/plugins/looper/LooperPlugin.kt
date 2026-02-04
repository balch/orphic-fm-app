package org.balch.orpheus.plugins.looper

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.ports

/**
 * DSP Plugin for Native Audio Looper.
 * Wraps JsynLooperUnit.
 * 
 * Port Map:
 * 0: Input Left (Audio)
 * 1: Input Right (Audio)
 * 2: Output Left (Audio)
 * 3: Output Right (Audio)
 * 4: Record Gate (Audio/Control)
 * 5: Play Gate (Audio/Control)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class LooperPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.looper",
        name = "Looper",
        author = "Balch"
    )

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Output Right"; isInput = false }
        // Gates can be treated as audio ports for sample-accurate control or control ports
        audioPort { index = 4; symbol = "record_gate"; name = "Record Gate"; isInput = true }
        audioPort { index = 5; symbol = "play_gate"; name = "Play Gate"; isInput = true }
    }

    override val ports: List<Port> = audioPorts.ports

    private val looper = dspFactory.createLooperUnit()
    
    // Internal proxies for clean input routing
    private val inputLeftProxy = dspFactory.createPassThrough()
    private val inputRightProxy = dspFactory.createPassThrough()

    override val audioUnits: List<AudioUnit> = listOf(
        looper, inputLeftProxy, inputRightProxy
    )

    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to inputLeftProxy.input,
        "inputRight" to inputRightProxy.input,
        "recordGate" to looper.recordGate,
        "playGate" to looper.playGate
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to looper.output,
        "outputRight" to looper.outputRight
    )

    override fun initialize() {
        looper.allocate(60.0)
        
        inputLeftProxy.output.connect(looper.inputLeft)
        inputRightProxy.output.connect(looper.inputRight)
        
        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}
    
    fun allocate(seconds: Double) { looper.allocate(seconds) }
    fun clear() { looper.clear() }
    fun setRecording(active: Boolean) { looper.setRecording(active) }
    fun setPlaying(active: Boolean) { looper.setPlaying(active) }
    fun getLoopDuration(): Double = looper.getLoopDuration()
    fun getPosition(): Float = looper.getPosition()
}
