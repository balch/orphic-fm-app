package org.balch.orpheus.plugins.stereo

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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exhaustive enum of all Stereo plugin port symbols.
 */
enum class StereoSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MASTER_PAN("master_pan", "Master Pan"),
    MASTER_VOL("master_vol", "Master Volume"),
    VOICE_PAN_0("voice_pan_0", "Voice 0 Pan"),
    VOICE_PAN_1("voice_pan_1", "Voice 1 Pan"),
    VOICE_PAN_2("voice_pan_2", "Voice 2 Pan"),
    VOICE_PAN_3("voice_pan_3", "Voice 3 Pan"),
    VOICE_PAN_4("voice_pan_4", "Voice 4 Pan"),
    VOICE_PAN_5("voice_pan_5", "Voice 5 Pan"),
    VOICE_PAN_6("voice_pan_6", "Voice 6 Pan"),
    VOICE_PAN_7("voice_pan_7", "Voice 7 Pan"),
    VOICE_PAN_8("voice_pan_8", "Voice 8 Pan"),
    VOICE_PAN_9("voice_pan_9", "Voice 9 Pan"),
    VOICE_PAN_10("voice_pan_10", "Voice 10 Pan"),
    VOICE_PAN_11("voice_pan_11", "Voice 11 Pan")
}

/**
 * Stereo Plugin (Output stage).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class StereoPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = URI,
        name = "Stereo Output",
        author = "Balch"
    )

    companion object {
        const val URI = "org.balch.orpheus.plugins.stereo"
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

    // Internal state
    private val _voicePan = FloatArray(12) { 0f }
    private var _masterPan = 0f
    private var _masterVolume = 0.7f

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 5) {
        controlPort(StereoSymbol.MASTER_PAN) {
            floatType {
                default = 0f; min = -1f; max = 1f
                get { _masterPan }
                set {
                    _masterPan = it.coerceIn(-1f, 1f)
                    val angle = ((it + 1f) / 2f) * (PI / 2).toFloat()
                    val leftGain = cos(angle.toDouble())
                    val rightGain = sin(angle.toDouble())
                    masterPanLeft.inputB.set(leftGain)
                    masterPanRight.inputB.set(rightGain)
                }
            }
        }
        
        controlPort(StereoSymbol.MASTER_VOL) {
            floatType {
                default = 0.7f
                get { _masterVolume }
                set {
                    _masterVolume = it
                    masterGainLeft.inputB.set(it.toDouble())
                    masterGainRight.inputB.set(it.toDouble())
                }
            }
        }
        
        // Voice pans 0-11
        for (i in 0 until 12) {
            controlPort(StereoSymbol.entries[i + 2]) { // Skip MASTER_PAN and MASTER_VOL
                floatType {
                    default = when(i) {
                        2 -> -0.3f; 3 -> -0.3f; 4 -> 0.3f; 5 -> 0.3f
                        6 -> -0.7f; 7 -> 0.7f
                        else -> 0f
                    }
                    min = -1f; max = 1f
                    get { _voicePan[i] }
                    set {
                        _voicePan[i] = it.coerceIn(-1f, 1f)
                        val angle = ((it + 1f) / 2f) * (PI / 2).toFloat()
                        val leftGain = cos(angle.toDouble())
                        val rightGain = sin(angle.toDouble())
                        voicePanLeft[i].inputB.set(leftGain)
                        voicePanRight[i].inputB.set(rightGain)
                    }
                }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "in_l"; name = "Left Input"; isInput = true }
        audioPort { index = 1; symbol = "in_r"; name = "Right Input"; isInput = true }
        audioPort { index = 2; symbol = "out_l"; name = "Left Output"; isInput = false }
        audioPort { index = 3; symbol = "out_r"; name = "Right Output"; isInput = false }
        audioPort { index = 4; symbol = "peak"; name = "Peak Monitor"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

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

        audioUnits.forEach { audioEngine.addUnit(it) }

        // Default voice pans via DSL
        portDefs.setValue(StereoSymbol.VOICE_PAN_0, PortValue.FloatValue(0f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_1, PortValue.FloatValue(0f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_2, PortValue.FloatValue(-0.3f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_3, PortValue.FloatValue(-0.3f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_4, PortValue.FloatValue(0.3f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_5, PortValue.FloatValue(0.3f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_6, PortValue.FloatValue(-0.7f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_7, PortValue.FloatValue(0.7f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_8, PortValue.FloatValue(0f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_9, PortValue.FloatValue(0f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_10, PortValue.FloatValue(0f))
        portDefs.setValue(StereoSymbol.VOICE_PAN_11, PortValue.FloatValue(0f))
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)


    fun getPeak(): Float = peakFollower.getCurrent().toFloat()
}
