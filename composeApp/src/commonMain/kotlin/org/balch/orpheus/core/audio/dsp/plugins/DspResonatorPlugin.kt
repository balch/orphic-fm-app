package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit

/**
 * DSP Plugin for Rings-style resonator (modal synthesis and string).
 * 
 * Provides:
 * - Modal resonator (SVF filter bank)
 * - Karplus-Strong string synthesis
 * - Dry/wet mixing
 * - Strum triggering
 * 
 * Signal path:
 * Input → ResonatorUnit → WetGain → WetOut
 *       ↘ Bypass → DryGain → DryOut
 * Mix sums WetOut + DryOut → Output
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspResonatorPlugin(
    private val audioEngine: AudioEngine
) : DspPlugin {
    
    // Core resonator unit
    private val resonator = audioEngine.createResonatorUnit()
    
    // Dry/wet mixing
    private val dryGainLeft = audioEngine.createMultiply()
    private val dryGainRight = audioEngine.createMultiply()
    private val wetGainLeft = audioEngine.createMultiply()
    private val wetGainRight = audioEngine.createMultiply()
    
    // Mix summers (dry + wet per channel)
    private val mixSumLeft = audioEngine.createAdd()
    private val mixSumRight = audioEngine.createAdd()
    
    // Input splitter (for stereo input handling)
    private val inputSumLeft = audioEngine.createPassThrough()
    private val inputSumRight = audioEngine.createPassThrough()
    
    // State
    private var _enabled = false
    private var _mode = 0
    private var _structure = 0.25f
    private var _brightness = 0.5f
    private var _damping = 0.3f
    private var _position = 0.5f
    private var _mix = 0.5f
    
    override val audioUnits: List<AudioUnit> = listOf(
        resonator,
        dryGainLeft, dryGainRight,
        wetGainLeft, wetGainRight,
        mixSumLeft, mixSumRight,
        inputSumLeft, inputSumRight
    )
    
    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            "inputLeft" to inputSumLeft.input,
            "inputRight" to inputSumRight.input
        )
    
    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "outputLeft" to mixSumLeft.output,
            "outputRight" to mixSumRight.output,
            "auxLeft" to resonator.auxOutput,
            "auxRight" to resonator.auxOutput
        )
    
    override fun initialize() {
        // Default mix (50/50 dry/wet)
        setMix(_mix)
        
        // Wire internal signal path
        // Input → Resonator
        inputSumLeft.output.connect(resonator.input)
        // Note: Resonator is mono input, we sum stereo to mono
        inputSumRight.output.connect(resonator.input) 
        
        // Resonator → Wet path
        resonator.output.connect(wetGainLeft.inputA)
        resonator.output.connect(wetGainRight.inputA)
        
        // Input → Dry path (bypass)
        inputSumLeft.output.connect(dryGainLeft.inputA)
        inputSumRight.output.connect(dryGainRight.inputA)
        
        // Wet + Dry → Mix
        wetGainLeft.output.connect(mixSumLeft.inputA)
        dryGainLeft.output.connect(mixSumLeft.inputB)
        wetGainRight.output.connect(mixSumRight.inputA)
        dryGainRight.output.connect(mixSumRight.inputB)
        
        // Apply initial settings
        resonator.setEnabled(_enabled)
        resonator.setMode(_mode)
        resonator.setStructure(_structure)
        resonator.setBrightness(_brightness)
        resonator.setDamping(_damping)
        resonator.setPosition(_position)
    }
    
    fun setEnabled(enabled: Boolean) {
        _enabled = enabled
        resonator.setEnabled(enabled)
    }
    
    fun setMode(mode: Int) {
        _mode = mode
        resonator.setMode(mode)
    }
    
    fun setStructure(value: Float) {
        _structure = value
        resonator.setStructure(value)
    }
    
    fun setBrightness(value: Float) {
        _brightness = value
        resonator.setBrightness(value)
    }
    
    fun setDamping(value: Float) {
        _damping = value
        resonator.setDamping(value)
    }
    
    fun setPosition(value: Float) {
        _position = value
        resonator.setPosition(value)
    }
    
    fun setMix(value: Float) {
        _mix = value.coerceIn(0f, 1f)
        val wetLevel = _mix.toDouble()
        val dryLevel = (1.0 - _mix).toDouble()
        
        wetGainLeft.inputB.set(wetLevel)
        wetGainRight.inputB.set(wetLevel)
        dryGainLeft.inputB.set(dryLevel)
        dryGainRight.inputB.set(dryLevel)
    }
    
    fun strum(frequency: Float) {
        resonator.strum(frequency)
    }
    
    // Getters for state saving
    fun getEnabled(): Boolean = _enabled
    fun getMode(): Int = _mode
    fun getStructure(): Float = _structure
    fun getBrightness(): Float = _brightness
    fun getDamping(): Float = _damping
    fun getPosition(): Float = _position
    fun getMix(): Float = _mix
}
