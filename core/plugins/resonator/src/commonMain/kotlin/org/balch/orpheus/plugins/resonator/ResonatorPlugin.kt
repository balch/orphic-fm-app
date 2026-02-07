package org.balch.orpheus.plugins.resonator

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
import org.balch.orpheus.core.audio.dsp.Symbol
import org.balch.orpheus.core.audio.dsp.ports
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.RESONATOR_URI
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol

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
        uri = URI,
        name = "Resonator",
        author = "Balch"
    )

    companion object {
        const val URI = RESONATOR_URI
    }

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
        controlPort(ResonatorSymbol.MODE) {
            intType {
                min = 0; max = 5
                options = listOf("Modal", "String", "Sympathetic", "Modaloid", "Stringoid", "Sympatheroid")
                get { _mode }
                set { _mode = it; resonator.setMode(it) }
            }
        }
        
        controlPort(ResonatorSymbol.TARGET_MIX) {
            floatType {
                default = 0f
                get { _targetMix }
                set { _targetMix = it.coerceIn(0f, 1f); applyTargetMixRouting() }
            }
        }
        
        controlPort(ResonatorSymbol.STRUCTURE) {
            floatType {
                default = 0.25f
                get { _structure }
                set { _structure = it; resonator.setStructure(it) }
            }
        }
        
        controlPort(ResonatorSymbol.BRIGHTNESS) {
            floatType {
                get { _brightness }
                set { _brightness = it; resonator.setBrightness(it) }
            }
        }
        
        controlPort(ResonatorSymbol.DAMPING) {
            floatType {
                default = 0.3f
                get { _damping }
                set { _damping = it; resonator.setDamping(it) }
            }
        }
        
        controlPort(ResonatorSymbol.POSITION) {
            floatType {
                get { _position }
                set { _position = it; resonator.setPosition(it) }
            }
        }
        
        controlPort(ResonatorSymbol.MIX) {
            floatType {
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
        }
        
        controlPort(ResonatorSymbol.SNAP_BACK) {
            boolType {
                get { _snapBack }
                set { _snapBack = it }
            }
        }
    }

    private val audioPorts = ports {
        audioPort { index = 0; symbol = "drum_l"; name = "Drum Left"; isInput = true }
        audioPort { index = 1; symbol = "drum_r"; name = "Drum Right"; isInput = true }
        audioPort { index = 2; symbol = "synth_l"; name = "Synth Left"; isInput = true }
        audioPort { index = 3; symbol = "synth_r"; name = "Synth Right"; isInput = true }
        audioPort { index = 4; symbol = "out_l"; name = "Output Left"; isInput = false }
        audioPort { index = 5; symbol = "out_r"; name = "Output Right"; isInput = false }
        audioPort { index = 6; symbol = "aux_l"; name = "Aux Left"; isInput = false }
        audioPort { index = 7; symbol = "aux_r"; name = "Aux Right"; isInput = false }
    }

    override val ports: List<Port> = audioPorts.ports + portDefs.controlPorts

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

    // Utility methods
    fun strum(frequency: Float) = resonator.strum(frequency)
}
