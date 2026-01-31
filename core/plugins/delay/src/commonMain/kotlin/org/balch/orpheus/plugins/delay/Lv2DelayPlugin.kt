package org.balch.orpheus.plugins.delay

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
import org.balch.orpheus.core.audio.dsp.lv2.AudioPort
import org.balch.orpheus.core.audio.dsp.lv2.ControlPort
import org.balch.orpheus.core.audio.dsp.lv2.PluginInfo
import org.balch.orpheus.core.audio.dsp.lv2.Port
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.Lv2DspPlugin

/**
 * LV2-style Delay Plugin.
 * 
 * Port Map:
 * 0: Audio In Left (Input)
 * 1: Audio In Right (Input)
 * 2: LFO In (Input)
 * 3: Wet 1 Left (Output)
 * 4: Wet 1 Right (Output)
 * 5: Wet 2 Left (Output)
 * 6: Wet 2 Right (Output)
 * 7: Feedback (Control Input, 0..1)
 * 8: Mix (Control Input, 0..1)
 * 9: Time 1 (Control Input, 0..1)
 * 10: Time 2 (Control Input, 0..1)
 * 11: Mod Depth 1 (Control Input, 0..1)
 * 12: Mod Depth 2 (Control Input, 0..1)
 * 13: Stereo Mode (Control Input, 0=Mono, 1=PingPong)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class Lv2DelayPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : Lv2DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.delay",
        name = "Dual Delay",
        author = "Orpheus"
    )

    // DSP Units
    private val delay1 = dspFactory.createDelayLine()
    private val delay2 = dspFactory.createDelayLine()
    private val delay1FeedbackGain = dspFactory.createMultiply()
    private val delay2FeedbackGain = dspFactory.createMultiply()
    private val lfoToUnipolar1 = dspFactory.createMultiplyAdd()
    private val lfoToUnipolar2 = dspFactory.createMultiplyAdd()
    private val delay1ModMixer = dspFactory.createMultiplyAdd()
    private val delay2ModMixer = dspFactory.createMultiplyAdd()
    private val delay1TimeRamp = dspFactory.createLinearRamp()
    private val delay2TimeRamp = dspFactory.createLinearRamp()
    private val delay1ModDepthRamp = dspFactory.createLinearRamp()
    private val delay2ModDepthRamp = dspFactory.createLinearRamp()
    private val delay1WetLeft = dspFactory.createMultiply()
    private val delay1WetRight = dspFactory.createMultiply()
    private val delay2WetLeft = dspFactory.createMultiply()
    private val delay2WetRight = dspFactory.createMultiply()
    private val selfMod1Attenuator = dspFactory.createMultiply()
    private val selfMod2Attenuator = dspFactory.createMultiply()
    private val inputLeftProxy = dspFactory.createPassThrough()
    private val inputRightProxy = dspFactory.createPassThrough()
    private val lfoInputProxy = dspFactory.createPassThrough()

    override val ports: List<Port> = listOf(
        AudioPort(0, "in_l", "Input Left", true),
        AudioPort(1, "in_r", "Input Right", true),
        AudioPort(2, "lfo_in", "LFO Input", true),
        AudioPort(3, "wet_1_l", "Wet 1 Left", false),
        AudioPort(4, "wet_1_r", "Wet 1 Right", false),
        AudioPort(5, "wet_2_l", "Wet 2 Left", false),
        AudioPort(6, "wet_2_r", "Wet 2 Right", false),
        ControlPort(7, "feedback", "Feedback", 0.5f, 0f, 1f),
        ControlPort(8, "mix", "Mix", 0.5f, 0f, 1f),
        ControlPort(9, "time_1", "Time 1", 0.3f, 0f, 1f),
        ControlPort(10, "time_2", "Time 2", 0.3f, 0f, 1f),
        ControlPort(11, "mod_depth_1", "Mod Depth 1", 0f, 0f, 1f),
        ControlPort(12, "mod_depth_2", "Mod Depth 2", 0f, 0f, 1f),
        ControlPort(13, "stereo_mode", "Stereo Mode", 0f, 0f, 1f)
    )

    private var _delayWetLevel = 0.5f
    private var _stereoDelaysMode = false

    override val audioUnits: List<AudioUnit> = listOf(
        delay1, delay2, delay1FeedbackGain, delay2FeedbackGain,
        lfoToUnipolar1, lfoToUnipolar2, delay1ModMixer, delay2ModMixer,
        delay1TimeRamp, delay2TimeRamp, delay1ModDepthRamp, delay2ModDepthRamp,
        delay1WetLeft, delay1WetRight, delay2WetLeft, delay2WetRight,
        selfMod1Attenuator, selfMod2Attenuator,
        inputLeftProxy, inputRightProxy, lfoInputProxy
    )

    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to inputLeftProxy.input,
        "inputRight" to inputRightProxy.input,
        "lfoInput" to lfoInputProxy.input
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "wetLeft" to delay1WetLeft.output,
        "wetRight" to delay1WetRight.output,
        "wet2Left" to delay2WetLeft.output,
        "wet2Right" to delay2WetRight.output,
        "delay1Output" to delay1.output,
        "delay2Output" to delay2.output
    )

    // Expose feedback gain inputs for automation (Compatibility with DspSynthEngine)
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
        // Wiring logic copied from DspDelayPlugin
        delay1.allocate(110250)
        delay2.allocate(110250)

        // Bipolar to Unipolar
        lfoInputProxy.output.connect(lfoToUnipolar1.inputA)
        lfoToUnipolar1.inputB.set(0.5)
        lfoToUnipolar1.inputC.set(0.5)
        lfoInputProxy.output.connect(lfoToUnipolar2.inputA)
        lfoToUnipolar2.inputB.set(0.5)
        lfoToUnipolar2.inputC.set(0.5)

        // Ramps
        delay1TimeRamp.time.set(0.02); delay2TimeRamp.time.set(0.02)
        delay1ModDepthRamp.time.set(0.02); delay2ModDepthRamp.time.set(0.02)

        // Mod Mixers
        lfoToUnipolar1.output.connect(delay1ModMixer.inputA)
        lfoToUnipolar2.output.connect(delay2ModMixer.inputA)
        delay1ModDepthRamp.output.connect(delay1ModMixer.inputB)
        delay2ModDepthRamp.output.connect(delay2ModMixer.inputB)
        delay1TimeRamp.output.connect(delay1ModMixer.inputC)
        delay2TimeRamp.output.connect(delay2ModMixer.inputC)

        delay1ModMixer.output.connect(delay1.delay)
        delay2ModMixer.output.connect(delay2.delay)

        inputLeftProxy.output.connect(delay1.input)
        inputRightProxy.output.connect(delay2.input)

        // Feedback
        delay1.output.connect(delay1FeedbackGain.inputA)
        delay1FeedbackGain.output.connect(delay1.input)
        delay2.output.connect(delay2FeedbackGain.inputA)
        delay2FeedbackGain.output.connect(delay2.input)

        // Wet Gains
        delay1.output.connect(delay1WetLeft.inputA)
        delay1.output.connect(delay1WetRight.inputA)
        delay2.output.connect(delay2WetLeft.inputA)
        delay2.output.connect(delay2WetRight.inputA)

        // Register with engine
        audioUnits.forEach { audioEngine.addUnit(it) }

        // Self-modulation setup
        selfMod1Attenuator.inputB.set(0.02)
        selfMod2Attenuator.inputB.set(0.02)
        delay1.output.connect(selfMod1Attenuator.inputA)
        delay2.output.connect(selfMod2Attenuator.inputA)

        // Initial values
        updateState()
    }

    private fun updateState() {
        // Here we would apply values from ControlPorts
        // For now let's just use the defaults from Port definition if not set
    }

    override fun connectPort(index: Int, data: Any) {
        // Implement connection logic if needed for external buffers,
        // but for internal graph we mostly use Ports to discover AudioInputs/Outputs.
    }

    override fun run(nFrames: Int) {
        // Update control parameters if they are driven by Float data instead of Audio-Rate signals
    }

    // Setters implementation
    fun setTime(index: Int, value: Float) {
        val seconds = 0.01 + (value.coerceIn(0f, 1f) * 1.99)
        if (index == 0) delay1TimeRamp.input.set(seconds) else delay2TimeRamp.input.set(seconds)
    }

    fun setFeedback(value: Float) {
        val fb = value.coerceIn(0f, 1f) * 0.95
        delay1FeedbackGain.inputB.set(fb)
        delay2FeedbackGain.inputB.set(fb)
    }

    fun setMix(value: Float) {
        _delayWetLevel = value.coerceIn(0f, 1f)
        updateStereoGains()
    }

    fun setModDepth(index: Int, value: Float) {
        val depth = value.coerceIn(0f, 1f) * 0.1
        if (index == 0) delay1ModDepthRamp.input.set(depth) else delay2ModDepthRamp.input.set(depth)
    }

    fun setModSource(index: Int, isLfo: Boolean) {
        val targetConverter = if (index == 0) lfoToUnipolar1 else lfoToUnipolar2
        targetConverter.inputA.disconnectAll()
        if (isLfo) {
            lfoInputProxy.output.connect(targetConverter.inputA)
        } else {
            val attenuatedSelf = if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetConverter.inputA)
        }
    }

    fun setStereoMode(pingPong: Boolean) {
        _stereoDelaysMode = pingPong
        updateStereoGains()
    }

    private fun updateStereoGains() {
        val gain = _delayWetLevel.toDouble()
        if (_stereoDelaysMode) {
            delay1WetLeft.inputB.set(gain); delay1WetRight.inputB.set(0.0)
            delay2WetLeft.inputB.set(0.0); delay2WetRight.inputB.set(gain)
        } else {
            delay1WetLeft.inputB.set(gain); delay1WetRight.inputB.set(gain)
            delay2WetLeft.inputB.set(gain); delay2WetRight.inputB.set(gain)
        }
    }

    // Getters for state saving
    fun getTime(index: Int): Float = ports.filterIsInstance<ControlPort>().find { it.index == 9 + index }?.default ?: 0.3f
    fun getFeedback(): Float = ports.filterIsInstance<ControlPort>().find { it.index == 7 }?.default ?: 0.5f
    fun getMix(): Float = _delayWetLevel
    fun getModDepth(index: Int): Float = ports.filterIsInstance<ControlPort>().find { it.index == 11 + index }?.default ?: 0f
    fun getModSourceIsLfo(index: Int): Boolean = true // TODO: track source
}
