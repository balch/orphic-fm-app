package org.balch.orpheus.plugins.distortion

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

/**
 * LV2-style Distortion Plugin.
 * 
 * Port Map:
 * 0: Audio In Left (Input)
 * 1: Audio In Right (Input)
 * 2: Audio Out Left (Output)
 * 3: Audio Out Right (Output)
 * 4: Drive (Control Input, 0..1)
 * 5: Mix (Control Input, 0..1)
 * 6: Dry Level (Control Input, 0..1)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DistortionPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.distortion",
        name = "Distortion",
        author = "Balch",
        version = "1.0.0"
    )

    override val ports: List<Port> = listOf(
        AudioPort(0, "in_l", "Input Left", true),
        AudioPort(1, "in_r", "Input Right", true),
        AudioPort(2, "out_l", "Output Left", false),
        AudioPort(3, "out_r", "Output Right", false),
        ControlPort(4, "drive", "Drive", 0.0f, 0f, 1f),
        ControlPort(5, "mix", "Mix", 0.5f, 0f, 1f),
        ControlPort(6, "dry_level", "Dry Level", 1.0f, 0f, 1f)
    )

    // Internal DSP Units
    private val drySumLeft = dspFactory.createPassThrough()
    private val drySumRight = dspFactory.createPassThrough()
    private val dryGainLeft = dspFactory.createMultiply()
    private val dryGainRight = dspFactory.createMultiply()
    private val driveGainLeft = dspFactory.createMultiply()
    private val driveGainRight = dspFactory.createMultiply()
    private val limiterLeft = dspFactory.createLimiter()
    private val limiterRight = dspFactory.createLimiter()
    private val cleanPathGainLeft = dspFactory.createMultiply()
    private val cleanPathGainRight = dspFactory.createMultiply()
    private val distortedPathGainLeft = dspFactory.createMultiply()
    private val distortedPathGainRight = dspFactory.createMultiply()
    private val postMixSummerLeft = dspFactory.createAdd()
    private val postMixSummerRight = dspFactory.createAdd()

    override val audioUnits: List<AudioUnit> = listOf(
        drySumLeft, drySumRight,
        dryGainLeft, dryGainRight,
        driveGainLeft, driveGainRight,
        limiterLeft, limiterRight,
        cleanPathGainLeft, cleanPathGainRight,
        distortedPathGainLeft, distortedPathGainRight,
        postMixSummerLeft, postMixSummerRight
    )

    // DspPlugin compatibility
    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to drySumLeft.input,
        "inputRight" to drySumRight.input
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "outputLeft" to postMixSummerLeft.output,
        "outputRight" to postMixSummerRight.output
    )

    // Automation compatibility
    val limiterLeftDrive: AudioInput get() = limiterLeft.drive
    val limiterRightDrive: AudioInput get() = limiterRight.drive
    val cleanPathLeftGain: AudioInput get() = cleanPathGainLeft.inputB
    val cleanPathRightGain: AudioInput get() = cleanPathGainRight.inputB
    val distortedPathLeftGain: AudioInput get() = distortedPathGainLeft.inputB
    val distortedPathRightGain: AudioInput get() = distortedPathGainRight.inputB
    val dryGainLeftInput: AudioInput get() = dryGainLeft.inputB
    val dryGainRightInput: AudioInput get() = dryGainRight.inputB

    // State caches
    private var _drive = 0.0f
    private var _distortionMix = 0.5f

    override fun initialize() {
        // Default drive
        driveGainLeft.inputB.set(1.0)
        driveGainRight.inputB.set(1.0)

        // Default clean/distorted mix (50/50)
        cleanPathGainLeft.inputB.set(0.5)
        cleanPathGainRight.inputB.set(0.5)
        distortedPathGainLeft.inputB.set(0.5)
        distortedPathGainRight.inputB.set(0.5)

        // Dry level defaults (full dry)
        dryGainLeft.inputB.set(1.0)
        dryGainRight.inputB.set(1.0)

        // LEFT CHANNEL wiring
        drySumLeft.output.connect(dryGainLeft.inputA)
        dryGainLeft.output.connect(cleanPathGainLeft.inputA)
        cleanPathGainLeft.output.connect(postMixSummerLeft.inputA)

        dryGainLeft.output.connect(driveGainLeft.inputA)
        driveGainLeft.output.connect(limiterLeft.input)
        limiterLeft.output.connect(distortedPathGainLeft.inputA)
        distortedPathGainLeft.output.connect(postMixSummerLeft.inputB)

        // RIGHT CHANNEL wiring
        drySumRight.output.connect(dryGainRight.inputA)
        dryGainRight.output.connect(cleanPathGainRight.inputA)
        cleanPathGainRight.output.connect(postMixSummerRight.inputA)

        dryGainRight.output.connect(driveGainRight.inputA)
        driveGainRight.output.connect(limiterRight.input)
        limiterRight.output.connect(distortedPathGainRight.inputA)
        distortedPathGainRight.output.connect(postMixSummerRight.inputB)

        // Register with engine
        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {
        // No-op for now
    }

    override fun connectPort(index: Int, data: Any) {
        // In this implementation, we mostly use internal graph wiring.
        // External connections will eventually use these ports.
    }

    override fun run(nFrames: Int) {
        // Update control parameters if they are driven by Float data instead of Audio-Rate signals
    }

    fun setDrive(amount: Float) {
        _drive = amount
        val driveVal = 1.0 + (amount * 14.0)
        limiterLeft.drive.set(driveVal)
        limiterRight.drive.set(driveVal)
    }

    fun setMix(amount: Float) {
        _distortionMix = amount
        val distortedLevel = amount
        val cleanLevel = 1.0f - amount
        cleanPathGainLeft.inputB.set(cleanLevel.toDouble())
        cleanPathGainRight.inputB.set(cleanLevel.toDouble())
        distortedPathGainLeft.inputB.set(distortedLevel.toDouble())
        distortedPathGainRight.inputB.set(distortedLevel.toDouble())
    }

    fun setDryLevel(amount: Float) {
        val level = amount.toDouble()
        dryGainLeft.inputB.set(level)
        dryGainRight.inputB.set(level)
    }

    // Getters for state saving
    fun getDrive(): Float = _drive
    fun getMix(): Float = _distortionMix
}
