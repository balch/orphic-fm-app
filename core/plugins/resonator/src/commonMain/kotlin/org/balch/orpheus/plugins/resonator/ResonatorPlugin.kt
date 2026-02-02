package org.balch.orpheus.plugins.resonator

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
 * Resonator Plugin (Modal synthesis and string).
 * 
 * Port Map:
 * 0: Drum Left (Input)
 * 1: Drum Right (Input)
 * 2: Synth Left (Input)
 * 3: Synth Right (Input)
 * 4: Output Left (Output)
 * 5: Output Right (Output)
 * 6: Aux Left (Output)
 * 7: Aux Right (Output)
 * 8: Mode (Control Input)
 * 9: Target Mix (Control Input, 0..1)
 * 10: Structure (Control Input, 0..1)
 * 11: Brightness (Control Input, 0..1)
 * 12: Damping (Control Input, 0..1)
 * 13: Position (Control Input, 0..1)
 * 14: Mix (Control Input, 0..1)
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class ResonatorPlugin(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory
) : DspPlugin {

    override val info = PluginInfo(
        uri = "org.balch.orpheus.plugins.resonator",
        name = "Resonator",
        author = "Balch"
    )

    override val ports: List<Port> = listOf(
        AudioPort(0, "drum_l", "Drum Left", true),
        AudioPort(1, "drum_r", "Drum Right", true),
        AudioPort(2, "synth_l", "Synth Left", true),
        AudioPort(3, "synth_r", "Synth Right", true),
        AudioPort(4, "out_l", "Output Left", false),
        AudioPort(5, "out_r", "Output Right", false),
        AudioPort(6, "aux_l", "Aux Left", false),
        AudioPort(7, "aux_r", "Aux Right", false),
        ControlPort(8, "mode", "Mode", 0f, 0f, 5f),
        ControlPort(9, "target_mix", "Target Mix", 0f, 0f, 1f),
        ControlPort(10, "structure", "Structure", 0.25f, 0f, 1f),
        ControlPort(11, "brightness", "Brightness", 0.5f, 0f, 1f),
        ControlPort(12, "damping", "Damping", 0.3f, 0f, 1f),
        ControlPort(13, "position", "Position", 0.5f, 0f, 1f),
        ControlPort(14, "mix", "Mix", 0.0f, 0f, 1f)
    )

    // Core resonator unit
    private val resonator = dspFactory.createResonatorUnit()
    
    // Excitation gains
    private val drumExciteGainL = dspFactory.createMultiply()
    private val drumExciteGainR = dspFactory.createMultiply()
    private val synthExciteGainL = dspFactory.createMultiply()
    private val synthExciteGainR = dspFactory.createMultiply()
    
    // Dry bypass gains
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

    override val audioUnits: List<AudioUnit> = listOf(
        resonator,
        drumExciteGainL, drumExciteGainR, synthExciteGainL, synthExciteGainR,
        drumBypassGainL, drumBypassGainR, synthBypassGainL, synthBypassGainR,
        excitationSumL, excitationSumR, bypassSumL, bypassSumR,
        wetGainL, wetGainR, dryGainL, dryGainR,
        resoMixL, resoMixR, finalSumL, finalSumR
    )

    // DspPlugin compatibility
    override val inputs: Map<String, AudioInput> = mapOf(
        "drumLeft" to drumExciteGainL.inputA,
        "drumRight" to drumExciteGainR.inputA,
        "synthLeft" to synthExciteGainL.inputA,
        "synthRight" to synthExciteGainR.inputA,
        "fullDrumLeft" to drumBypassGainL.inputA,
        "fullDrumRight" to drumBypassGainR.inputA,
        "fullSynthLeft" to synthBypassGainL.inputA,
        "fullSynthRight" to synthBypassGainR.inputA
    )

    override val outputs: Map<String, AudioOutput> = mapOf(
        "outputLeft" to finalSumL.output,
        "outputRight" to finalSumR.output,
        "auxLeft" to resonator.auxOutput,
        "auxRight" to resonator.auxOutput
    )

    // State caches
    private var _mode = 0
    private var _targetMix = 0.0f
    private var _structure = 0.25f
    private var _brightness = 0.5f
    private var _damping = 0.3f
    private var _position = 0.5f
    private var _mix = 0.0f
    private var _snapBack = false

    override fun initialize() {
        // Excitation path: gated sources -> sum -> resonator
        drumExciteGainL.output.connect(excitationSumL.inputA)
        synthExciteGainL.output.connect(excitationSumL.inputB)
        drumExciteGainR.output.connect(excitationSumR.inputA)
        synthExciteGainR.output.connect(excitationSumR.inputB)
        
        excitationSumL.output.connect(resonator.input)
        excitationSumR.output.connect(resonator.input)
        
        // Bypass path: inverse-gated sources -> sum
        drumBypassGainL.output.connect(bypassSumL.inputA)
        synthBypassGainL.output.connect(bypassSumL.inputB)
        drumBypassGainR.output.connect(bypassSumR.inputA)
        synthBypassGainR.output.connect(bypassSumR.inputB)
        
        // Resonator wet/dry: excitationSum -> dryGain, resonator -> wetGain
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
        
        // Apply initial settings
        resonator.setEnabled(true)
        resonator.setMode(_mode)
        resonator.setStructure(_structure)
        resonator.setBrightness(_brightness)
        resonator.setDamping(_damping)
        resonator.setPosition(_position)
        setMix(_mix)
        applyTargetMixRouting()

        // Register with engine
        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {
        // No-op
    }

    override fun connectPort(index: Int, data: Any) {
        // External connections
    }

    override fun run(nFrames: Int) {
        // Block processing
    }

    private fun applyTargetMixRouting() {
        val drumExcite = if (_targetMix <= 0.5f) 1.0 else (1.0 - (_targetMix.toDouble() - 0.5) * 2.0).coerceIn(0.0, 1.0)
        val synthExcite = if (_targetMix >= 0.5f) 1.0 else (_targetMix.toDouble() * 2.0).coerceIn(0.0, 1.0)
        
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

    /**
     * Compatibility helper for discrete target selection.
     * 0 = Drums, 1 = Both, 2 = Synth
     */
    fun setTarget(target: Int) {
        val mix = when (target.coerceIn(0, 2)) {
            0 -> 0.0f
            1 -> 0.5f
            2 -> 1.0f
            else -> 0.5f
        }
        setTargetMix(mix)
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

    fun setSnapBack(enabled: Boolean) {
        _snapBack = enabled
    }

    fun strum(frequency: Float) {
        resonator.strum(frequency)
    }

    // Getters for state saving
    fun getMode(): Int = _mode
    fun getTarget(): Int {
        return when {
            _targetMix <= 0.3f -> 0
            _targetMix >= 0.7f -> 2
            else -> 1
        }
    }
    fun getTargetMix(): Float = _targetMix
    fun getStructure(): Float = _structure
    fun getBrightness(): Float = _brightness
    fun getDamping(): Float = _damping
    fun getPosition(): Float = _position
    fun getMix(): Float = _mix
    fun getSnapBack(): Boolean = _snapBack
}
