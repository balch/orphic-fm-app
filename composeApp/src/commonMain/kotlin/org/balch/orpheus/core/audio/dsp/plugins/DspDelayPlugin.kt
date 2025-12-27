package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * DSP Plugin for dual delay lines with modulation.
 * 
 * Features:
 * - Two independent delay lines with feedback
 * - LFO or self-modulation sources
 * - LinearRamp smoothing for click-free parameter changes
 * - Stereo wet output gains
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspDelayPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {

    // Delay Lines
    private val delay1 = audioEngine.createDelayLine()
    private val delay2 = audioEngine.createDelayLine()
    private val delay1FeedbackGain = audioEngine.createMultiply()
    private val delay2FeedbackGain = audioEngine.createMultiply()

    // Bipolar to Unipolar converters for LFO: u = x * 0.5 + 0.5
    private val lfoToUnipolar1 = audioEngine.createMultiplyAdd()
    private val lfoToUnipolar2 = audioEngine.createMultiplyAdd()
    
    // Modulation mixers: (UnipolarLFO * Depth) + BaseTime
    private val delay1ModMixer = audioEngine.createMultiplyAdd()
    private val delay2ModMixer = audioEngine.createMultiplyAdd()

    // LinearRamp units for smooth parameter transitions
    private val delay1TimeRamp = audioEngine.createLinearRamp()
    private val delay2TimeRamp = audioEngine.createLinearRamp()
    private val delay1ModDepthRamp = audioEngine.createLinearRamp()
    private val delay2ModDepthRamp = audioEngine.createLinearRamp()

    // Self-modulation attenuators
    private val selfMod1Attenuator = audioEngine.createMultiply()
    private val selfMod2Attenuator = audioEngine.createMultiply()

    // Stereo wet gains
    private val delay1WetLeft = audioEngine.createMultiply()
    private val delay1WetRight = audioEngine.createMultiply()
    private val delay2WetLeft = audioEngine.createMultiply()
    private val delay2WetRight = audioEngine.createMultiply()

    // Input proxy for voices → delays
    private val inputProxy = audioEngine.createPassThrough()
    
    // LFO input proxy
    private val lfoInputProxy = audioEngine.createPassThrough()

    // State caches
    private val _delayTime = FloatArray(2) { 0.3f }
    private var _delayFeedback = 0.5f
    private var _delayMix = 0.5f
    private val _delayModDepth = FloatArray(2) { 0.0f }
    private val _delayModSourceIsLfo = BooleanArray(2) { true }
    private var _delayWetLevel = 0.5f
    private var _stereoDelaysMode = false

    override val audioUnits: List<AudioUnit> = listOf(
        delay1, delay2,
        delay1FeedbackGain, delay2FeedbackGain,
        lfoToUnipolar1, lfoToUnipolar2,
        delay1ModMixer, delay2ModMixer,
        delay1TimeRamp, delay2TimeRamp,
        delay1ModDepthRamp, delay2ModDepthRamp,
        selfMod1Attenuator, selfMod2Attenuator,
        delay1WetLeft, delay1WetRight,
        delay2WetLeft, delay2WetRight,
        inputProxy, lfoInputProxy
    )

    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "input" to inputProxy.input,
            "lfoInput" to lfoInputProxy.input
        )

    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "wetLeft" to delay1WetLeft.output,
            "wetRight" to delay1WetRight.output,
            "wet2Left" to delay2WetLeft.output,
            "wet2Right" to delay2WetRight.output,
            "delay1Output" to delay1.output,
            "delay2Output" to delay2.output
        )

    // Expose feedback gain inputs for automation
    val delay1FeedbackInput: AudioInput get() = delay1FeedbackGain.inputB
    val delay2FeedbackInput: AudioInput get() = delay2FeedbackGain.inputB
    val delay1TimeRampInput: AudioInput get() = delay1TimeRamp.input
    val delay2TimeRampInput: AudioInput get() = delay2TimeRamp.input
    val delay1ModDepthRampInput: AudioInput get() = delay1ModDepthRamp.input
    val delay2ModDepthRampInput: AudioInput get() = delay2ModDepthRamp.input
    val delay1WetLeftGain: AudioInput get() = delay1WetLeft.inputB
    val delay1WetRightGain: AudioInput get() = delay1WetRight.inputB
    val delay2WetLeftGain: AudioInput get() = delay2WetLeft.inputB
    val delay2WetRightGain: AudioInput get() = delay2WetRight.inputB

    override fun initialize() {
        // Allocate delay buffers (2.5 seconds max at 44.1kHz)
        delay1.allocate(110250)
        delay2.allocate(110250)

        // Self-modulation attenuator (2% of audio signal reaches mod input)
        selfMod1Attenuator.inputB.set(0.02)
        selfMod2Attenuator.inputB.set(0.02)
        delay1.output.connect(selfMod1Attenuator.inputA)
        delay2.output.connect(selfMod2Attenuator.inputA)

        // Bipolar to Unipolar: u = x * 0.5 + 0.5
        lfoInputProxy.output.connect(lfoToUnipolar1.inputA)
        lfoToUnipolar1.inputB.set(0.5)
        lfoToUnipolar1.inputC.set(0.5)

        lfoInputProxy.output.connect(lfoToUnipolar2.inputA)
        lfoToUnipolar2.inputB.set(0.5)
        lfoToUnipolar2.inputC.set(0.5)

        // Configure LinearRamps (20ms ramp time)
        delay1TimeRamp.time.set(0.02)
        delay2TimeRamp.time.set(0.02)
        delay1ModDepthRamp.time.set(0.02)
        delay2ModDepthRamp.time.set(0.02)

        // Initialize ramps with default values
        delay1TimeRamp.input.set(0.3)
        delay2TimeRamp.input.set(0.3)
        delay1ModDepthRamp.input.set(0.0)
        delay2ModDepthRamp.input.set(0.0)

        // Connect unipolar LFO to modulation mixers
        // Mod mixer formula: (LFO * ModDepth) + BaseTime
        lfoToUnipolar1.output.connect(delay1ModMixer.inputA)
        lfoToUnipolar2.output.connect(delay2ModMixer.inputA)

        // Wire ramp outputs to mod mixer inputs
        delay1ModDepthRamp.output.connect(delay1ModMixer.inputB)
        delay2ModDepthRamp.output.connect(delay2ModMixer.inputB)
        delay1TimeRamp.output.connect(delay1ModMixer.inputC)
        delay2TimeRamp.output.connect(delay2ModMixer.inputC)

        delay1ModMixer.output.connect(delay1.delay)
        delay2ModMixer.output.connect(delay2.delay)

        // Input proxy → both delays
        inputProxy.output.connect(delay1.input)
        inputProxy.output.connect(delay2.input)

        // Feedback loops
        delay1.output.connect(delay1FeedbackGain.inputA)
        delay1FeedbackGain.output.connect(delay1.input)
        delay2.output.connect(delay2FeedbackGain.inputA)
        delay2FeedbackGain.output.connect(delay2.input)

        // Stereo wet gains
        delay1.output.connect(delay1WetLeft.inputA)
        delay1.output.connect(delay1WetRight.inputA)
        delay2.output.connect(delay2WetLeft.inputA)
        delay2.output.connect(delay2WetRight.inputA)

        // Set defaults
        setFeedback(0.5f)
        setMix(0.5f)
    }

    fun setTime(index: Int, time: Float) {
        _delayTime[index] = time
        val safeTime = time.coerceAtLeast(0.005f)
        val delaySeconds = 0.01 + (safeTime * 1.99)
        if (index == 0) {
            delay1TimeRamp.input.set(delaySeconds)
        } else {
            delay2TimeRamp.input.set(delaySeconds)
        }
    }

    fun setFeedback(amount: Float) {
        _delayFeedback = amount
        val fb = amount * 0.95
        delay1FeedbackGain.inputB.set(fb)
        delay2FeedbackGain.inputB.set(fb)
    }

    fun setMix(amount: Float) {
        _delayMix = amount
        _delayWetLevel = amount
        updateStereoGains()
    }

    fun setModDepth(index: Int, amount: Float) {
        _delayModDepth[index] = amount
        val depth = amount * 0.1
        if (index == 0) {
            delay1ModDepthRamp.input.set(depth)
        } else {
            delay2ModDepthRamp.input.set(depth)
        }
    }

    fun setModSource(index: Int, isLfo: Boolean) {
        _delayModSourceIsLfo[index] = isLfo
        val targetConverter = if (index == 0) lfoToUnipolar1 else lfoToUnipolar2

        targetConverter.inputA.disconnectAll()

        if (isLfo) {
            lfoInputProxy.output.connect(targetConverter.inputA)
        } else {
            val attenuatedSelf = if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetConverter.inputA)
        }
    }

    fun setStereoMode(stereoDelays: Boolean) {
        _stereoDelaysMode = stereoDelays
        updateStereoGains()
    }

    private fun updateStereoGains() {
        if (_stereoDelaysMode) {
            // Ping Pong: Delay1→Left, Delay2→Right
            val gain = _delayWetLevel.toDouble()
            delay1WetLeft.inputB.set(gain)
            delay1WetRight.inputB.set(0.0)
            delay2WetLeft.inputB.set(0.0)
            delay2WetRight.inputB.set(gain)
        } else {
            // Mono: Both delays to both channels
            val gain = _delayWetLevel.toDouble()
            delay1WetLeft.inputB.set(gain)
            delay1WetRight.inputB.set(gain)
            delay2WetLeft.inputB.set(gain)
            delay2WetRight.inputB.set(gain)
        }
    }

    // Getters for state saving
    fun getTime(index: Int): Float = _delayTime[index]
    fun getFeedback(): Float = _delayFeedback
    fun getMix(): Float = _delayMix
    fun getModDepth(index: Int): Float = _delayModDepth[index]
    fun getModSourceIsLfo(index: Int): Boolean = _delayModSourceIsLfo[index]
}
