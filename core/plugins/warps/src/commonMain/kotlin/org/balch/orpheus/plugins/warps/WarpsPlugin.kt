package org.balch.orpheus.plugins.warps

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
 * Warps Meta-Modulator Plugin.
 * 
 * Port Map:
 * 0: Carrier Input (Audio)
 * 1: Modulator Input (Audio)
 * 2: Output Left (Audio)
 * 3: Output Right (Audio)
 * 4: Algorithm (Control Input, 0..8)
 * 5: Timbre (Control Input, 0..1)
 * 6: Level 1 (Control Input, 0..1)
 * 7: Level 2 (Control Input, 0..1)
 * 8: Mix (Control Input, 0..1)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class WarpsPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.warps",
        name = "Warps",
        author = "Balch"
    )

    override val ports: List<Port> = listOf(
        AudioPort(0, "carrier", "Carrier", true),
        AudioPort(1, "modulator", "Modulator", true),
        AudioPort(2, "out_l", "Output Left", false),
        AudioPort(3, "out_r", "Output Right", false),
        ControlPort(4, "algorithm", "Algorithm", 0f, 0f, 8f),
        ControlPort(5, "timbre", "Timbre", 0.5f, 0f, 1f),
        ControlPort(6, "level1", "Level 1", 0.5f, 0f, 1f),
        ControlPort(7, "level2", "Level 2", 0.5f, 0f, 1f),
        ControlPort(8, "mix", "Mix", 0.5f, 0f, 1f)
    )

    private val warps = dspFactory.createWarpsUnit()
    
    // Source routing pass-throughs
    private val carrierInput = dspFactory.createPassThrough()
    private val modulatorInput = dspFactory.createPassThrough()
    
    // Dry/Wet Mix routing
    private val dryGainLeft = dspFactory.createMultiply()
    private val dryGainRight = dspFactory.createMultiply()
    private val wetGainLeft = dspFactory.createMultiply()
    private val wetGainRight = dspFactory.createMultiply()
    private val mixSumLeft = dspFactory.createPassThrough()
    private val mixSumRight = dspFactory.createPassThrough()
    
    // Dry signal pass-throughs
    private val dryPassLeft = dspFactory.createPassThrough()
    private val dryPassRight = dspFactory.createPassThrough()
    
    // State tracking
    private var _carrierSource = 0 // WarpsSource.SYNTH
    private var _modulatorSource = 1 // WarpsSource.DRUMS
    private var _mix = 0.5f
    
    override val audioUnits: List<AudioUnit> = listOf(
        warps, carrierInput, modulatorInput,
        dryGainLeft, dryGainRight, wetGainLeft, wetGainRight,
        mixSumLeft, mixSumRight, dryPassLeft, dryPassRight
    )
    
    val carrierRouteInput: AudioInput get() = carrierInput.input
    val modulatorRouteInput: AudioInput get() = modulatorInput.input
    val dryInputLeft: AudioInput get() = dryPassLeft.input
    val dryInputRight: AudioInput get() = dryPassRight.input
    
    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to carrierInput.input,
        "inputRight" to modulatorInput.input,
        "algorithm" to warps.algorithm,
        "timbre" to warps.timbre,
        "level1" to warps.level1,
        "level2" to warps.level2
    )
    
    override val outputs: Map<String, AudioOutput> = mapOf(
        "output" to mixSumLeft.output,
        "outputRight" to mixSumRight.output
    )
    
    override fun initialize() {
        carrierInput.output.connect(warps.inputLeft)
        modulatorInput.output.connect(warps.inputRight)
        
        warps.output.connect(wetGainLeft.inputA)
        warps.outputRight.connect(wetGainRight.inputA)
        wetGainLeft.output.connect(mixSumLeft.input)
        wetGainRight.output.connect(mixSumRight.input)
        
        dryPassLeft.output.connect(dryGainLeft.inputA)
        dryPassRight.output.connect(dryGainRight.inputA)
        dryGainLeft.output.connect(mixSumLeft.input)
        dryGainRight.output.connect(mixSumRight.input)
        
        warps.algorithm.set(0.0)
        warps.timbre.set(0.5)
        warps.level1.set(0.5)
        warps.level2.set(0.5)
        
        setMix(0.5f)

        audioUnits.forEach { audioEngine.addUnit(it) }
    }
    
    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    fun setAlgorithm(value: Float) = warps.algorithm.set(value.toDouble())
    fun setTimbre(value: Float) = warps.timbre.set(value.toDouble())
    fun setLevel1(value: Float) = warps.level1.set(value.toDouble())
    fun setLevel2(value: Float) = warps.level2.set(value.toDouble())
    
    fun setMix(value: Float) {
        _mix = value
        val wet = value.toDouble()
        val dry = (1.0 - value).toDouble()
        wetGainLeft.inputB.set(wet)
        wetGainRight.inputB.set(wet)
        dryGainLeft.inputB.set(dry)
        dryGainRight.inputB.set(dry)
    }
    
    fun getMix(): Float = _mix
    fun setCarrierSource(source: Int) { _carrierSource = source }
    fun getCarrierSource(): Int = _carrierSource
    fun setModulatorSource(source: Int) { _modulatorSource = source }
    fun getModulatorSource(): Int = _modulatorSource
    
    fun disconnectCarrier() { carrierInput.input.disconnectAll() }
    fun disconnectModulator() { modulatorInput.input.disconnectAll() }
    fun disconnectDry() {
        dryPassLeft.input.disconnectAll()
        dryPassRight.input.disconnectAll()
    }
}
