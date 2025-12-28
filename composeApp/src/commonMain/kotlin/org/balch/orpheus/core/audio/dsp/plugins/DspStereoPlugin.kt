package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DSP Plugin for stereo output stage.
 * 
 * Features:
 * - Stereo sum buses
 * - Per-voice panning (8 voices, equal-power pan law)
 * - Master pan and volume
 * - Peak monitoring
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspStereoPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    // Stereo sum buses
    private val stereoSumLeft = audioEngine.createPassThrough()
    private val stereoSumRight = audioEngine.createPassThrough()
    
    // Master output
    private val masterGainLeft = audioEngine.createMultiply()
    private val masterGainRight = audioEngine.createMultiply()
    private val masterPanLeft = audioEngine.createMultiply()
    private val masterPanRight = audioEngine.createMultiply()
    
    // Per-voice panning (12 voices)
    private val voicePanLeft = List(12) { audioEngine.createMultiply() }
    private val voicePanRight = List(12) { audioEngine.createMultiply() }
    
    // Peak monitoring
    private val peakFollower = audioEngine.createPeakFollower()

    // State caches
    private val _voicePan = FloatArray(12) { 0f }
    private var _masterPan = 0f
    private var _masterVolume = 0.7f
    
    // Default voice pan positions (bass center, mids slight L/R, highs wide, REPL center)
    private val defaultVoicePans = floatArrayOf(
        0f, 0f,       // Quad 1 Pair 1 (Bass)
        -0.3f, -0.3f, // Quad 1 Pair 2
        0.3f, 0.3f,   // Quad 2 Pair 3
        -0.7f, 0.7f,  // Quad 2 Pair 4
        0f, 0f,       // Quad 3 Pair 5 (REPL)
        0f, 0f        // Quad 3 Pair 6 (REPL)
    )

    override val audioUnits: List<AudioUnit> = listOf(
        stereoSumLeft, stereoSumRight,
        masterGainLeft, masterGainRight,
        masterPanLeft, masterPanRight,
        peakFollower
    ) + voicePanLeft + voicePanRight

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "dryInputLeft" to stereoSumLeft.input,
            "dryInputRight" to stereoSumRight.input
        )

    // Expose voice pan outputs for external connection (to distortion)
    fun getVoicePanOutputLeft(index: Int): AudioOutput = voicePanLeft[index].output
    fun getVoicePanOutputRight(index: Int): AudioOutput = voicePanRight[index].output

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "lineOutLeft" to masterGainLeft.output,
            "lineOutRight" to masterGainRight.output,
            "peakOutput" to peakFollower.output
        )

    // Expose for automation
    val masterGainLeftInput: AudioInput get() = masterGainLeft.inputB
    val masterGainRightInput: AudioInput get() = masterGainRight.inputB

    /** Get voice pan input for connection by DspSynthEngine */
    fun getVoicePanInputLeft(index: Int): AudioInput = voicePanLeft[index].inputA
    fun getVoicePanInputRight(index: Int): AudioInput = voicePanRight[index].inputA

    override fun initialize() {
        // Master defaults
        masterGainLeft.inputB.set(0.7)
        masterGainRight.inputB.set(0.7)
        masterPanLeft.inputB.set(1.0)  // Center (equal L/R)
        masterPanRight.inputB.set(1.0)

        // Peak follower setup
        peakFollower.setHalfLife(0.1)

        // Stereo Sum → Master Pan → Master Gain → LineOut
        stereoSumLeft.output.connect(masterPanLeft.inputA)
        stereoSumRight.output.connect(masterPanRight.inputA)
        masterPanLeft.output.connect(masterGainLeft.inputA)
        masterPanRight.output.connect(masterGainRight.inputA)

        // Peak follower monitors left channel
        masterGainLeft.output.connect(peakFollower.input)

        // NOTE: voicePan outputs are NOT connected to stereoSum here!
        // DspSynthEngine wires: voicePan → distortion → stereoSum
        // Direct wet paths (delays) can connect to stereoSum directly

        // Apply default voice pan positions
        defaultVoicePans.forEachIndexed { index, pan ->
            setVoicePan(index, pan)
        }
    }

    fun setVoicePan(index: Int, pan: Float) {
        _voicePan[index] = pan.coerceIn(-1f, 1f)
        // Equal-power pan law: L = cos(angle), R = sin(angle)
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

    // Getters for state saving
    fun getVoicePan(index: Int): Float = _voicePan[index]
    fun getMasterPan(): Float = _masterPan
    fun getMasterVolume(): Float = _masterVolume
}
