package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * DSP Plugin for stereo distortion with drive and mix control.
 * 
 * Signal path:
 * InputL/R → DryGain → Parallel split:
 *   → CleanPath → PostMixSummer
 *   → DriveGain → Limiter → DistortedPath → PostMixSummer
 * PostMixSummer → OutputL/R
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspDistortionPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    // Stereo dry sum buses
    private val drySumLeft = audioEngine.createPassThrough()
    private val drySumRight = audioEngine.createPassThrough()
    
    // Dry level control
    private val dryGainLeft = audioEngine.createMultiply()
    private val dryGainRight = audioEngine.createMultiply()
    
    // Drive amount
    private val driveGainLeft = audioEngine.createMultiply()
    private val driveGainRight = audioEngine.createMultiply()
    
    // Limiters (saturation)
    private val limiterLeft = audioEngine.createLimiter()
    private val limiterRight = audioEngine.createLimiter()
    
    // Clean/Distorted mix paths
    private val cleanPathGainLeft = audioEngine.createMultiply()
    private val cleanPathGainRight = audioEngine.createMultiply()
    private val distortedPathGainLeft = audioEngine.createMultiply()
    private val distortedPathGainRight = audioEngine.createMultiply()
    
    // Post-mix summers
    private val postMixSummerLeft = audioEngine.createAdd()
    private val postMixSummerRight = audioEngine.createAdd()

    // State caches
    private var _drive = 0.0f
    private var _distortionMix = 0.5f

    override val audioUnits: List<AudioUnit> = listOf(
        drySumLeft, drySumRight,
        dryGainLeft, dryGainRight,
        driveGainLeft, driveGainRight,
        limiterLeft, limiterRight,
        cleanPathGainLeft, cleanPathGainRight,
        distortedPathGainLeft, distortedPathGainRight,
        postMixSummerLeft, postMixSummerRight
    )

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "inputLeft" to drySumLeft.input,
            "inputRight" to drySumRight.input
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "outputLeft" to postMixSummerLeft.output,
            "outputRight" to postMixSummerRight.output
        )

    // Expose for automation
    val limiterLeftDrive: AudioInput get() = limiterLeft.drive
    val limiterRightDrive: AudioInput get() = limiterRight.drive
    val cleanPathLeftGain: AudioInput get() = cleanPathGainLeft.inputB
    val cleanPathRightGain: AudioInput get() = cleanPathGainRight.inputB
    val distortedPathLeftGain: AudioInput get() = distortedPathGainLeft.inputB
    val distortedPathRightGain: AudioInput get() = distortedPathGainRight.inputB
    val dryGainLeftInput: AudioInput get() = dryGainLeft.inputB
    val dryGainRightInput: AudioInput get() = dryGainRight.inputB

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