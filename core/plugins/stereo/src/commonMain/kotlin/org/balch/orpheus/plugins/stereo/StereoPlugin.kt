package org.balch.orpheus.plugins.stereo

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
import org.balch.orpheus.core.audio.dsp.ControlPort
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stereo Plugin (Output stage).
 * 
 * Port Map:
 * 0: Left Input (Audio)
 * 1: Right Input (Audio)
 * 2: Left Line Output (Audio)
 * 3: Right Line Output (Audio)
 * 4: Peak Output (Control Output)
 * 5: Master Pan (Control Input, -1..1)
 * 6: Master Volume (Control Input, 0..1)
 * 7..18: Voice Pan (Control Input, -1..1)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class StereoPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.stereo",
        name = "Stereo Output",
        author = "Balch"
    )

    override val ports: List<Port> = buildList {
        add(AudioPort(0, "in_l", "Left Input", true))
        add(AudioPort(1, "in_r", "Right Input", true))
        add(AudioPort(2, "out_l", "Left Output", false))
        add(AudioPort(3, "out_r", "Right Output", false))
        add(ControlPort(4, "peak", "Peak Monitor", 0f, 0f, 2f))
        add(ControlPort(5, "master_pan", "Master Pan", 0f, -1f, 1f))
        add(ControlPort(6, "master_vol", "Master Volume", 0.7f, 0f, 1f))
        for (i in 0 until 12) {
            add(ControlPort(7 + i, "voice_pan_$i", "Voice $i Pan", 0f, -1f, 1f))
        }
    }

    // Summing buses
    private val stereoSumLeft = dspFactory.createPassThrough()
    private val stereoSumRight = dspFactory.createPassThrough()
    
    // Master output
    private val masterGainLeft = dspFactory.createMultiply()
    private val masterGainRight = dspFactory.createMultiply()
    private val masterPanLeft = dspFactory.createMultiply()
    private val masterPanRight = dspFactory.createMultiply()
    
    // Per-voice panning (12 voices)
    private val voicePanLeft = List(12) { dspFactory.createMultiply() }
    private val voicePanRight = List(12) { dspFactory.createMultiply() }
    
    // Peak monitoring
    private val peakFollower = dspFactory.createPeakFollower()

    // State caches
    private val _voicePan = FloatArray(12) { 0f }
    private var _masterPan = 0f
    private var _masterVolume = 0.7f

    override val audioUnits: List<AudioUnit> = listOf(
        stereoSumLeft, stereoSumRight,
        masterGainLeft, masterGainRight,
        masterPanLeft, masterPanRight,
        peakFollower
    ) + voicePanLeft + voicePanRight

    override val inputs: Map<String, AudioInput> = mapOf(
        "dryInputLeft" to stereoSumLeft.input,
        "dryInputRight" to stereoSumRight.input
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "lineOutLeft" to masterGainLeft.output,
        "lineOutRight" to masterGainRight.output,
        "peakOutput" to peakFollower.output
    )

    // Bridge methods
    val masterGainLeftInput: AudioInput get() = masterGainLeft.inputB
    val masterGainRightInput: AudioInput get() = masterGainRight.inputB
    fun getVoicePanInputLeft(index: Int): AudioInput = voicePanLeft[index].inputA
    fun getVoicePanInputRight(index: Int): AudioInput = voicePanRight[index].inputA
    fun getVoicePanOutputLeft(index: Int): AudioOutput = voicePanLeft[index].output
    fun getVoicePanOutputRight(index: Int): AudioOutput = voicePanRight[index].output

    override fun initialize() {
        // Master defaults
        masterGainLeft.inputB.set(0.7)
        masterGainRight.inputB.set(0.7)
        masterPanLeft.inputB.set(1.0)
        masterPanRight.inputB.set(1.0)

        // Peak follower
        peakFollower.setHalfLife(0.1)

        // Sum -> Master Pan -> Master Gain -> LineOut
        stereoSumLeft.output.connect(masterPanLeft.inputA)
        stereoSumRight.output.connect(masterPanRight.inputA)
        masterPanLeft.output.connect(masterGainLeft.inputA)
        masterPanRight.output.connect(masterGainRight.inputA)

        // Peak follower monitors left channel
        masterGainLeft.output.connect(peakFollower.input)

        // Register with engine
        audioUnits.forEach { audioEngine.addUnit(it) }

        // Default voice pans
        setVoicePan(0, 0f)
        setVoicePan(1, 0f)
        setVoicePan(2, -0.3f)
        setVoicePan(3, -0.3f)
        setVoicePan(4, 0.3f)
        setVoicePan(5, 0.3f)
        setVoicePan(6, -0.7f)
        setVoicePan(7, 0.7f)
        setVoicePan(8, 0f)
        setVoicePan(9, 0f)
        setVoicePan(10, 0f)
        setVoicePan(11, 0f)
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun setVoicePan(index: Int, pan: Float) {
        if (index !in 0 until 12) return
        _voicePan[index] = pan.coerceIn(-1f, 1f)
        val angle = ((pan + 1f) / 2f) * (PI / 2).toFloat()
        val leftGain = cos(angle.toDouble())
        val rightGain = sin(angle.toDouble())
        voicePanLeft[index].inputB.set(leftGain)
        voicePanRight[index].inputB.set(rightGain)
    }

    fun setMasterPan(pan: Float) {
        _masterPan = pan.coerceIn(-1f, 1f)
        val angle = ((pan + 1f) / 2f) * (PI / 2).toFloat()
        val leftGain = cos(angle.toDouble())
        val rightGain = sin(angle.toDouble())
        masterPanLeft.inputB.set(leftGain)
        masterPanRight.inputB.set(rightGain)
    }

    fun setMasterVolume(amount: Float) {
        _masterVolume = amount
        masterGainLeft.inputB.set(amount.toDouble())
        masterGainRight.inputB.set(amount.toDouble())
    }

    fun getPeak(): Float = peakFollower.getCurrent().toFloat()
    fun getVoicePan(index: Int): Float = _voicePan[index]
    fun getMasterPan(): Float = _masterPan
    fun getMasterVolume(): Float = _masterVolume
}
