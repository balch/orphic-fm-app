package org.balch.orpheus.core.audio.dsp.plugins

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.AudioInput
import org.balch.orpheus.core.audio.dsp.AudioOutput
import org.balch.orpheus.core.audio.dsp.AudioUnit
import org.balch.orpheus.core.audio.dsp.DspFactory

/**
 * DSP Plugin for Rings-style resonator (modal synthesis and string).
 * 
 * Signal flow with fader-based routing:
 * - Fader at 0 (DRM): Drums → Resonator, Synth → Dry bypass
 * - Fader at 0.5 (BOTH): Both → Resonator
 * - Fader at 1 (SYN): Synth → Resonator, Drums → Dry bypass
 * 
 * The "dry bypass" path carries signals that SKIP the resonator entirely.
 * The MIX knob controls wet/dry blend of the RESONATED signal only.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DspResonatorPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {
    
    // Core resonator unit
    private val resonator = dspFactory.createResonatorUnit()
    
    // Excitation gains (what goes TO the resonator, controlled by fader)
    private val drumExciteGainL = dspFactory.createMultiply()
    private val drumExciteGainR = dspFactory.createMultiply()
    private val synthExciteGainL = dspFactory.createMultiply()
    private val synthExciteGainR = dspFactory.createMultiply()
    
    // Dry bypass gains (what SKIPS the resonator, inverse of excitation)
    private val drumBypassGainL = dspFactory.createMultiply()
    private val drumBypassGainR = dspFactory.createMultiply()
    private val synthBypassGainL = dspFactory.createMultiply()
    private val synthBypassGainR = dspFactory.createMultiply()
    
    // Summing units
    private val excitationSumL = dspFactory.createAdd()
    private val excitationSumR = dspFactory.createAdd()
    private val bypassSumL = dspFactory.createAdd()
    private val bypassSumR = dspFactory.createAdd()
    
    // Wet/Dry mix for resonator output
    private val wetGainL = dspFactory.createMultiply()
    private val wetGainR = dspFactory.createMultiply()
    private val dryGainL = dspFactory.createMultiply()
    private val dryGainR = dspFactory.createMultiply()
    
    // Mix resonated (wet+dry) with bypass
    private val resoMixL = dspFactory.createAdd()
    private val resoMixR = dspFactory.createAdd()
    private val finalSumL = dspFactory.createAdd()
    private val finalSumR = dspFactory.createAdd()
    
    // State
    private var _mode = 0
    private var _target = 1
    private var _targetMix = 0.0f
    private var _structure = 0.25f
    private var _brightness = 0.5f
    private var _damping = 0.3f
    private var _position = 0.5f
    private var _mix = 0.0f
    
    override val audioUnits: List<AudioUnit> = listOf(
        resonator,
        drumExciteGainL, drumExciteGainR, synthExciteGainL, synthExciteGainR,
        drumBypassGainL, drumBypassGainR, synthBypassGainL, synthBypassGainR,
        excitationSumL, excitationSumR, bypassSumL, bypassSumR,
        wetGainL, wetGainR, dryGainL, dryGainR,
        resoMixL, resoMixR, finalSumL, finalSumR
    )
    
    override val inputs: Map<String, AudioInput>
        get() = mapOf(
            // Excitation path inputs
            "drumLeft" to drumExciteGainL.inputA,
            "drumRight" to drumExciteGainR.inputA,
            "synthLeft" to synthExciteGainL.inputA,
            "synthRight" to synthExciteGainR.inputA,
            // Bypass path inputs (same source, different gain)
            "fullDrumLeft" to drumBypassGainL.inputA,
            "fullDrumRight" to drumBypassGainR.inputA,
            "fullSynthLeft" to synthBypassGainL.inputA,
            "fullSynthRight" to synthBypassGainR.inputA
        )
    
    override val outputs: Map<String, AudioOutput>
        get() = mapOf(
            "outputLeft" to finalSumL.output,
            "outputRight" to finalSumR.output,
            "auxLeft" to resonator.auxOutput,
            "auxRight" to resonator.auxOutput
        )
    
    override fun initialize() {
        // Excitation path: gated sources → sum → resonator
        drumExciteGainL.output.connect(excitationSumL.inputA)
        synthExciteGainL.output.connect(excitationSumL.inputB)
        drumExciteGainR.output.connect(excitationSumR.inputA)
        synthExciteGainR.output.connect(excitationSumR.inputB)
        
        excitationSumL.output.connect(resonator.input)
        excitationSumR.output.connect(resonator.input)
        
        // Bypass path: inverse-gated sources → sum
        drumBypassGainL.output.connect(bypassSumL.inputA)
        synthBypassGainL.output.connect(bypassSumL.inputB)
        drumBypassGainR.output.connect(bypassSumR.inputA)
        synthBypassGainR.output.connect(bypassSumR.inputB)
        
        // Resonator wet/dry: excitationSum → dryGain, resonator → wetGain
        excitationSumL.output.connect(dryGainL.inputA)
        excitationSumR.output.connect(dryGainR.inputA)
        resonator.output.connect(wetGainL.inputA)
        resonator.output.connect(wetGainR.inputA)
        
        // Mix wet + dry of resonated signal
        wetGainL.output.connect(resoMixL.inputA)
        dryGainL.output.connect(resoMixL.inputB)
        wetGainR.output.connect(resoMixR.inputA)
        dryGainR.output.connect(resoMixR.inputB)
        
        // Final: resonated mix + bypass
        resoMixL.output.connect(finalSumL.inputA)
        bypassSumL.output.connect(finalSumL.inputB)
        resoMixR.output.connect(finalSumR.inputA)
        bypassSumR.output.connect(finalSumR.inputB)
        
        // Apply initial settings (always enabled)
        resonator.setEnabled(true)
        resonator.setMode(_mode)
        resonator.setStructure(_structure)
        resonator.setBrightness(_brightness)
        resonator.setDamping(_damping)
        resonator.setPosition(_position)
        setMix(_mix)
        applyTargetMixRouting()
    }
    
    private fun applyTargetRouting() {
        val drumExcite = when (_target) { 0 -> 1.0; 1 -> 1.0; 2 -> 0.0; else -> 1.0 }
        val synthExcite = when (_target) { 0 -> 0.0; 1 -> 1.0; 2 -> 1.0; else -> 1.0 }
        val drumBypass = 1.0 - drumExcite
        val synthBypass = 1.0 - synthExcite
        
        drumExciteGainL.inputB.set(drumExcite)
        drumExciteGainR.inputB.set(drumExcite)
        synthExciteGainL.inputB.set(synthExcite)
        synthExciteGainR.inputB.set(synthExcite)
        drumBypassGainL.inputB.set(drumBypass)
        drumBypassGainR.inputB.set(drumBypass)
        synthBypassGainL.inputB.set(synthBypass)
        synthBypassGainR.inputB.set(synthBypass)
    }
    
    private fun applyTargetMixRouting() {
        // Fader: 0=Drums to resonator, 1=Synth to resonator
        // Excitation gains
        val drumExcite = if (_targetMix <= 0.5f) 1.0 else (1.0 - (_targetMix.toDouble() - 0.5) * 2.0).coerceIn(0.0, 1.0)
        val synthExcite = if (_targetMix >= 0.5f) 1.0 else (_targetMix.toDouble() * 2.0).coerceIn(0.0, 1.0)
        
        // Bypass gains are INVERSE: what's NOT in resonator goes to bypass
        val drumBypass = 1.0 - drumExcite
        val synthBypass = 1.0 - synthExcite
        
        drumExciteGainL.inputB.set(drumExcite)
        drumExciteGainR.inputB.set(drumExcite)
        synthExciteGainL.inputB.set(synthExcite)
        synthExciteGainR.inputB.set(synthExcite)
        drumBypassGainL.inputB.set(drumBypass)
        drumBypassGainR.inputB.set(drumBypass)
        synthBypassGainL.inputB.set(synthBypass)
        synthBypassGainR.inputB.set(synthBypass)
    }
    
    fun setMode(mode: Int) {
        _mode = mode
        resonator.setMode(mode)
    }
    
    fun setTarget(target: Int) {
        _target = target.coerceIn(0, 2)
        applyTargetRouting()
    }
    
    fun setTargetMix(targetMix: Float) {
        _targetMix = targetMix.coerceIn(0f, 1f)
        applyTargetMixRouting()
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
        
        wetGainL.inputB.set(wetLevel)
        wetGainR.inputB.set(wetLevel)
        dryGainL.inputB.set(dryLevel)
        dryGainR.inputB.set(dryLevel)
    }
    
    fun strum(frequency: Float) {
        resonator.strum(frequency)
    }
    
    // Getters for state saving
    fun getMode(): Int = _mode
    fun getTarget(): Int = _target
    fun getTargetMix(): Float = _targetMix
    fun getStructure(): Float = _structure
    fun getBrightness(): Float = _brightness
    fun getDamping(): Float = _damping
    fun getPosition(): Float = _position
    fun getMix(): Float = _mix
    fun getSnapBack(): Boolean = _snapBack
    
    // UI State persistence only (doesn't affect DSP)
    private var _snapBack = false
    fun setSnapBack(enabled: Boolean) {
        _snapBack = enabled
    }
}
