package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.synth.warps.WarpsSource

/**
 * Plugin wrapping the Warps Meta-Modulator with dynamic source routing.
 * 
 * The carrier and modulator inputs can be routed from various audio sources
 * in the synth engine (synth voices, drums, delay, grains, resonator, etc.)
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspWarpsPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    private val warps = dspFactory.createWarpsUnit()
    
    // Source routing pass-throughs for dynamic connection
    private val carrierInput = dspFactory.createPassThrough()
    private val modulatorInput = dspFactory.createPassThrough()
    
    // Dry/Wet Mix routing
    private val dryGainLeft = dspFactory.createMultiply()
    private val dryGainRight = dspFactory.createMultiply()
    private val wetGainLeft = dspFactory.createMultiply()
    private val wetGainRight = dspFactory.createMultiply()
    private val mixSumLeft = dspFactory.createPassThrough()
    private val mixSumRight = dspFactory.createPassThrough()
    
    // Dry signal pass-throughs (receives the carrier/modulator sum for dry path)
    private val dryPassLeft = dspFactory.createPassThrough()
    private val dryPassRight = dspFactory.createPassThrough()
    
    // State tracking
    private var _carrierSource = WarpsSource.SYNTH.ordinal
    private var _modulatorSource = WarpsSource.DRUMS.ordinal
    private var _mix = 0.5f
    
    override val audioUnits: List<AudioUnit> = listOf(
        warps,
        carrierInput,
        modulatorInput,
        dryGainLeft,
        dryGainRight,
        wetGainLeft,
        wetGainRight,
        mixSumLeft,
        mixSumRight,
        dryPassLeft,
        dryPassRight
    )
    
    // Expose the routing inputs for the engine to connect sources
    val carrierRouteInput: AudioInput get() = carrierInput.input
    val modulatorRouteInput: AudioInput get() = modulatorInput.input
    
    // Expose the dry inputs (should be connected to the same sources as carrier for proper dry path)
    val dryInputLeft: AudioInput get() = dryPassLeft.input
    val dryInputRight: AudioInput get() = dryPassRight.input
    
    override val inputs: Map<String, AudioInput> = mapOf(
        "inputLeft" to carrierInput.input,      // Legacy: carrier
        "inputRight" to modulatorInput.input,   // Legacy: modulator
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
        // Wire internal routing: pass-throughs -> warps
        carrierInput.output.connect(warps.inputLeft)
        modulatorInput.output.connect(warps.inputRight)
        
        // Wire dry/wet mix routing
        // Wet path: warps output -> wet gain -> mix sum
        warps.output.connect(wetGainLeft.inputA)
        warps.outputRight.connect(wetGainRight.inputA)
        wetGainLeft.output.connect(mixSumLeft.input)
        wetGainRight.output.connect(mixSumRight.input)
        
        // Dry path: dry pass -> dry gain -> mix sum  
        dryPassLeft.output.connect(dryGainLeft.inputA)
        dryPassRight.output.connect(dryGainRight.inputA)
        dryGainLeft.output.connect(mixSumLeft.input)
        dryGainRight.output.connect(mixSumRight.input)
        
        // Default settings
        warps.algorithm.set(0.0)
        warps.timbre.set(0.5)
        warps.level1.set(0.5)
        warps.level2.set(0.5)
        
        // Default mix: 50% wet
        setMix(0.5f)
    }
    
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
    
    fun setCarrierSource(source: Int) {
        _carrierSource = source
    }
    
    fun getCarrierSource(): Int = _carrierSource
    
    fun setModulatorSource(source: Int) {
        _modulatorSource = source
    }
    
    fun getModulatorSource(): Int = _modulatorSource
    
    /**
     * Disconnect all sources from the carrier input.
     * Called before reconnecting to a new source.
     */
    fun disconnectCarrier() {
        carrierInput.input.disconnectAll()
    }
    
    /**
     * Disconnect all sources from the modulator input.
     * Called before reconnecting to a new source.
     */
    fun disconnectModulator() {
        modulatorInput.input.disconnectAll()
    }
    
    /**
     * Disconnect all dry inputs.
     */
    fun disconnectDry() {
        dryPassLeft.input.disconnectAll()
        dryPassRight.input.disconnectAll()
    }
}
