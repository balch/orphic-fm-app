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
import org.balch.orpheus.core.audio.dsp.DspFactory
import org.balch.orpheus.core.audio.dsp.DspPlugin
import org.balch.orpheus.core.audio.dsp.PluginInfo
import org.balch.orpheus.core.audio.dsp.Port
import org.balch.orpheus.core.audio.dsp.PortSymbol
import org.balch.orpheus.core.audio.dsp.PortValue
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports

/**
 * Exhaustive enum of all Resonator plugin port symbols.
 */
enum class ResonatorSymbol(
    override val symbol: Symbol,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    MODE("mode", "Mode"),
    TARGET_MIX("target_mix", "Target Mix"),
    STRUCTURE("structure", "Structure"),
    BRIGHTNESS("brightness", "Brightness"),
    DAMPING("damping", "Damping"),
    POSITION("position", "Position"),
    MIX("mix", "Mix"),
    SNAP_BACK("snap_back", "Snap Back")
}

/**
 * Resonator Plugin (Modal synthesis and string).
 * 
 * Port Map:
 * 0-7: Audio ports (drum in, synth in, outputs, aux)
 * 
 * Controls (via DSL):
 * - mode, target_mix, structure, brightness, damping, position, mix, snap_back
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

    // Internal state
    private var _mode = 0
    private var _targetMix = 0.0f
    private var _structure = 0.25f
    private var _brightness = 0.5f
    private var _damping = 0.3f
    private var _position = 0.5f
    private var _mix = 0.0f
    private var _snapBack = false

    // Type-safe DSL port definitions
    private val portDefs = ports(startIndex = 8) {
        int(ResonatorSymbol.MODE) {
            min = 0; max = 5
            options = listOf("Modal", "String", "Sympathetic", "Modaloid", "Stringoid", "Sympatheroid")
            get { _mode }
            set { _mode = it; resonator.setMode(it) }
        }
        
        float(ResonatorSymbol.TARGET_MIX) {
            default = 0f
            get { _targetMix }
            set { _targetMix = it.coerceIn(0f, 1f); applyTargetMixRouting() }
        }
        
        float(ResonatorSymbol.STRUCTURE) {
            default = 0.25f
            get { _structure }
            set { _structure = it; resonator.setStructure(it) }
        }
        
        float(ResonatorSymbol.BRIGHTNESS) {
            get { _brightness }
            set { _brightness = it; resonator.setBrightness(it) }
        }
        
        float(ResonatorSymbol.DAMPING) {
            default = 0.3f
            get { _damping }
            set { _damping = it; resonator.setDamping(it) }
        }
        
        float(ResonatorSymbol.POSITION) {
            get { _position }
            set { _position = it; resonator.setPosition(it) }
        }
        
        float(ResonatorSymbol.MIX) {
            default = 0.0f
            get { _mix }
            set {
                _mix = it.coerceIn(0f, 1f)
                val wetLevel = it.toDouble()
                val dryLevel = (1.0 - it).toDouble()
                wetGainL.inputB.set(wetLevel)
                wetGainR.inputB.set(wetLevel)
                dryGainL.inputB.set(dryLevel)
                dryGainR.inputB.set(dryLevel)
            }
        }
        
        bool(ResonatorSymbol.SNAP_BACK) {
            get { _snapBack }
            set { _snapBack = it }
        }
    }

    private val audioPorts = listOf(
        AudioPort(0, "drum_l", "Drum Left", true),
        AudioPort(1, "drum_r", "Drum Right", true),
        AudioPort(2, "synth_l", "Synth Left", true),
        AudioPort(3, "synth_r", "Synth Right", true),
        AudioPort(4, "out_l", "Output Left", false),
        AudioPort(5, "out_r", "Output Right", false),
        AudioPort(6, "aux_l", "Aux Left", false),
        AudioPort(7, "aux_r", "Aux Right", false)
    )

    override val ports: List<Port> = audioPorts + portDefs.ports

    override val audioUnits: List<AudioUnit> = listOf(
        resonator,
        drumExciteGainL, drumExciteGainR, synthExciteGainL, synthExciteGainR,
        drumBypassGainL, drumBypassGainR, synthBypassGainL, synthBypassGainR,
        excitationSumL, excitationSumR, bypassSumL, bypassSumR,
        wetGainL, wetGainR, dryGainL, dryGainR,
        resoMixL, resoMixR, finalSumL, finalSumR
    )

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
        portDefs.setValue(ResonatorSymbol.MIX, PortValue.FloatValue(_mix))
        applyTargetMixRouting()

        audioUnits.forEach { audioEngine.addUnit(it) }
    }

    override fun onStart() {}
    override fun connectPort(index: Int, data: Any) {}
    override fun run(nFrames: Int) {}

    // Generic port value accessors delegating to DSL builder
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)

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

    // Legacy setters for backward compatibility
    fun setMode(mode: Int) = portDefs.setValue(ResonatorSymbol.MODE, PortValue.IntValue(mode))
    fun setTarget(target: Int) {
        val mix = when (target.coerceIn(0, 2)) {
            0 -> 0.0f
            1 -> 0.5f
            2 -> 1.0f
            else -> 0.5f
        }
        setTargetMix(mix)
    }
    fun setTargetMix(targetMix: Float) = portDefs.setValue(ResonatorSymbol.TARGET_MIX, PortValue.FloatValue(targetMix))
    fun setStructure(value: Float) = portDefs.setValue(ResonatorSymbol.STRUCTURE, PortValue.FloatValue(value))
    fun setBrightness(value: Float) = portDefs.setValue(ResonatorSymbol.BRIGHTNESS, PortValue.FloatValue(value))
    fun setDamping(value: Float) = portDefs.setValue(ResonatorSymbol.DAMPING, PortValue.FloatValue(value))
    fun setPosition(value: Float) = portDefs.setValue(ResonatorSymbol.POSITION, PortValue.FloatValue(value))
    fun setMix(value: Float) = portDefs.setValue(ResonatorSymbol.MIX, PortValue.FloatValue(value))
    fun setSnapBack(enabled: Boolean) = portDefs.setValue(ResonatorSymbol.SNAP_BACK, PortValue.BoolValue(enabled))

    fun strum(frequency: Float) = resonator.strum(frequency)

    // Getters for state saving
    fun getMode(): Int = _mode
    fun getTarget(): Int = when {
        _targetMix <= 0.3f -> 0
        _targetMix >= 0.7f -> 2
        else -> 1
    }
    fun getTargetMix(): Float = _targetMix
    fun getStructure(): Float = _structure
    fun getBrightness(): Float = _brightness
    fun getDamping(): Float = _damping
    fun getPosition(): Float = _position
    fun getMix(): Float = _mix
    fun getSnapBack(): Boolean = _snapBack
}
