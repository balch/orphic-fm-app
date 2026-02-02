package org.balch.orpheus.plugins.looper

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioPort
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port

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

    override val ports: List<Port> = listOf(
        AudioPort(0, "in_l", "Input Left", true),
        AudioPort(1, "in_r", "Input Right", true),
        AudioPort(2, "out_l", "Output Left", false),
        AudioPort(3, "out_r", "Output Right", false),
        // Gates can be treated as audio ports for sample-accurate control or control ports
        AudioPort(4, "record_gate", "Record Gate", true),
        AudioPort(5, "play_gate", "Play Gate", true)
    )

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
