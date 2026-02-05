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
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports

/**
 * Exhaustive enum of all Delay plugin port symbols.
 */
enum class DelaySymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    FEEDBACK("feedback", "Feedback"),
    MIX("mix", "Mix"),
    TIME_1("time_1", "Time 1"),
    TIME_2("time_2", "Time 2"),
    MOD_DEPTH_1("mod_depth_1", "Mod Depth 1"),
    MOD_DEPTH_2("mod_depth_2", "Mod Depth 2"),
    STEREO_MODE("stereo_mode", "Stereo Mode"),
    MOD_SOURCE("mod_source_is_lfo", "Mod Source is LFO"),
    LFO_WAVEFORM("lfo_wave_is_triangle", "LFO Waveform is Triangle")
}

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
 * 
 * Controls (via DSL):
 * - feedback, mix, time_1, time_2, mod_depth_1, mod_depth_2, stereo_mode
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class DelayPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Dual Delay",
        author = "Orpheus"
    )

    companion object {
        const val URI = "org.balch.orpheus.plugins.delay"
    }

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

    // Internal state
    private var _feedback = 0.5f
    private var _mix = 0.5f
    private var _time1 = 0.3f
    private var _time2 = 0.3f
    private var _modDepth1 = 0f
    private var _modDepth2 = 0f
    private var _stereoMode = false
    private var _modSourceIsLfo = true
    private var _lfoWaveformIsTriangle = true

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 7) {
        controlPort(DelaySymbol.FEEDBACK) {
            floatType {
                default = 0.5f
                get { _feedback }
                set { 
                    _feedback = it
                    val fb = it.coerceIn(0f, 1f) * 0.95
                    delay1FeedbackGain.inputB.set(fb)
                    delay2FeedbackGain.inputB.set(fb)
                }
            }
        }
        
        controlPort(DelaySymbol.MIX) {
            floatType {
                get { _mix }
                set {
                    _mix = it.coerceIn(0f, 1f)
                    updateStereoGains()
                }
            }
        }
        
        controlPort(DelaySymbol.TIME_1) {
            floatType {
                default = 0.3f
                get { _time1 }
                set {
                    _time1 = it
                    val seconds = 0.01 + (it.coerceIn(0f, 1f) * 1.99)
                    delay1TimeRamp.input.set(seconds)
                }
            }
        }
        
        controlPort(DelaySymbol.TIME_2) {
            floatType {
                default = 0.3f
                get { _time2 }
                set {
                    _time2 = it
                    val seconds = 0.01 + (it.coerceIn(0f, 1f) * 1.99)
                    delay2TimeRamp.input.set(seconds)
                }
            }
        }
        
        controlPort(DelaySymbol.MOD_DEPTH_1) {
            floatType {
                default = 0f
                get { _modDepth1 }
                set {
                    _modDepth1 = it
                    val depth = it.coerceIn(0f, 1f) * 0.1
                    delay1ModDepthRamp.input.set(depth)
                }
            }
        }
        
        controlPort(DelaySymbol.MOD_DEPTH_2) {
            floatType {
                default = 0f
                get { _modDepth2 }
                set {
                    _modDepth2 = it
                    val depth = it.coerceIn(0f, 1f) * 0.1
                    delay2ModDepthRamp.input.set(depth)
                }
            }
        }
        
        controlPort(DelaySymbol.STEREO_MODE) {
            boolType {
                default = false
                get { _stereoMode }
                set {
                    _stereoMode = it
                    updateStereoGains()
                }
            }
        }

        controlPort(DelaySymbol.MOD_SOURCE) {
            boolType {
                default = true
                get { _modSourceIsLfo }
                set {
                    _modSourceIsLfo = it
                    setModSourceInternal(0, it)
                    setModSourceInternal(1, it)
                }
            }
        }

        controlPort(DelaySymbol.LFO_WAVEFORM) {
            boolType {
                default = true
                get { _lfoWaveformIsTriangle }
                set { _lfoWaveformIsTriangle = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Input Left"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Input Right"; isInput = true }
        audioPort { index = 2; symbol = "lfo_in"; name = "LFO Input"; isInput = true }
        audioPort { index = 3; symbol = "wet_1_l"; name = "Wet 1 Left"; isInput = false }
        audioPort { index = 4; symbol = "wet_1_r"; name = "Wet 1 Right"; isInput = false }
        audioPort { index = 5; symbol = "wet_2_l"; name = "Wet 2 Left"; isInput = false }
        audioPort { index = 6; symbol = "wet_2_r"; name = "Wet 2 Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts


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

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

    // Internal helpers for DSL port setters
    private fun setModSourceInternal(index: Int, isLfo: Boolean) {
        val targetConverter = if (index == 0) lfoToUnipolar1 else lfoToUnipolar2
        targetConverter.inputA.disconnectAll()
        if (isLfo) {
            lfoInputProxy.output.connect(targetConverter.inputA)
        } else {
            val attenuatedSelf = if (index == 0) selfMod1Attenuator.output else selfMod2Attenuator.output
            attenuatedSelf.connect(targetConverter.inputA)
        }
    }

    private fun updateStereoGains() {
        val gain = _mix.toDouble()
        if (_stereoMode) {
            delay1WetLeft.inputB.set(gain); delay1WetRight.inputB.set(0.0)
            delay2WetLeft.inputB.set(0.0); delay2WetRight.inputB.set(gain)
        } else {
            delay1WetLeft.inputB.set(gain); delay1WetRight.inputB.set(gain)
            delay2WetLeft.inputB.set(gain); delay2WetRight.inputB.set(gain)
        }
    }
}
