package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.tempo.GlobalTempo

/**
 * Manages the static wiring of the DSP graph.
 * Handles bus creation, plugin interconnection, and voice routing.
 */
@SingleIn(AppScope::class)
class DspWiringGraph @Inject constructor(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory,
    private val pluginProvider: DspPluginProvider,
    private val globalTempo: GlobalTempo
) {
    private val log = logging("DspWiringGraph")

    // TOTAL FB: Output → LFO Frequency Modulation
    val totalFbGain = dspFactory.createMultiply()
    
    // Voice sum buses (for feeding into Grains before resonator)
    val voiceSumLeft = dspFactory.createPassThrough()
    val voiceSumRight = dspFactory.createPassThrough()
    
    // REPL Sum buses (Voices 8-11)
    val replSumLeft = dspFactory.createPassThrough()
    val replSumRight = dspFactory.createPassThrough()

    // Drum routing gains
    val drumChainGainL = dspFactory.createMultiply()
    val drumChainGainR = dspFactory.createMultiply()
    val drumDirectGainL = dspFactory.createMultiply()
    val drumDirectGainR = dspFactory.createMultiply()

    // Drum Direct Distortion (Parallel Limiter)
    val drumDirectLimiterL = dspFactory.createLimiter()
    val drumDirectLimiterR = dspFactory.createLimiter()
    
    // Drum Direct Resonator (Parallel)
    val drumDirectResonator = dspFactory.createResonatorUnit()
    val drumDirectResoWetGainL = dspFactory.createMultiply()
    val drumDirectResoWetGainR = dspFactory.createMultiply()
    val drumDirectResoDryGainL = dspFactory.createMultiply()
    val drumDirectResoDryGainR = dspFactory.createMultiply()
    val drumDirectResoSumL = dspFactory.createAdd()
    val drumDirectResoSumR = dspFactory.createAdd()

    fun initialize(voiceManager: DspVoiceManager) {
        log.debug { "Initializing DspWiringGraph" }
        
        registerUnits()
        wirePlugins()
        wireDrums()
        wireVoices(voiceManager)
        
        // Defaults
        totalFbGain.inputB.set(0.0) 
        initDrumDirectResonator()
    }
    
    private fun registerUnits() {
        // Register all plugin audio units
        pluginProvider.plugins.forEach { plugin ->
            plugin.audioUnits.forEach { unit ->
                audioEngine.addUnit(unit)
            }
        }
        
        // Register local units
        audioEngine.addUnit(totalFbGain)
        audioEngine.addUnit(voiceSumLeft)
        audioEngine.addUnit(voiceSumRight)
        audioEngine.addUnit(replSumLeft)
        audioEngine.addUnit(replSumRight)
        audioEngine.addUnit(drumChainGainL)
        audioEngine.addUnit(drumChainGainR)
        audioEngine.addUnit(drumDirectGainL)
        audioEngine.addUnit(drumDirectGainR)
        audioEngine.addUnit(drumDirectLimiterL)
        audioEngine.addUnit(drumDirectLimiterR)
        
        audioEngine.addUnit(drumDirectResonator)
        audioEngine.addUnit(drumDirectResoWetGainL)
        audioEngine.addUnit(drumDirectResoWetGainR)
        audioEngine.addUnit(drumDirectResoDryGainL)
        audioEngine.addUnit(drumDirectResoDryGainR)
        audioEngine.addUnit(drumDirectResoSumL)
        audioEngine.addUnit(drumDirectResoSumR)
    }
    
    private fun wirePlugins() {
        // Initialize all plugins (sets up internal wiring)
        pluginProvider.plugins.forEach { it.initialize() }

        // TOTAL FB: StereoPlugin.peak → scaled → HyperLfo.feedbackInput
        pluginProvider.stereoPlugin.outputs["peakOutput"]?.connect(totalFbGain.inputA)
        totalFbGain.output.connect(pluginProvider.hyperLfo.feedbackInput)

        // HyperLFO → Delay (modulation)
        pluginProvider.hyperLfo.output.connect(pluginProvider.delayPlugin.inputs["lfoInput"]!!)

        // Voice Sum → Grains (parallel granular path for voices only)
        voiceSumLeft.output.connect(pluginProvider.grainsPlugin.inputs["inputLeft"]!!)
        voiceSumRight.output.connect(pluginProvider.grainsPlugin.inputs["inputRight"]!!)
        
        // Grains → Stereo Sum (granular texture output) AND Looper input
        pluginProvider.grainsPlugin.outputs["output"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.grainsPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.grainsPlugin.outputs["output"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.grainsPlugin.outputs["outputRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        
        // Grains → Delay (Send)
        pluginProvider.grainsPlugin.outputs["output"]?.connect(pluginProvider.delayPlugin.inputs["inputLeft"]!!)
        pluginProvider.grainsPlugin.outputs["outputRight"]?.connect(pluginProvider.delayPlugin.inputs["inputRight"]!!)

        // Distortion → Stereo Sum (resonator path output) AND Looper input
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        
        // Distortion → Delay (Send)
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.delayPlugin.inputs["inputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.delayPlugin.inputs["inputRight"]!!)

        // Distortion → Reverb (Parallel Send)
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.reverbPlugin.inputs["inputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.reverbPlugin.inputs["inputRight"]!!)

        // Reverb → Stereo Sum AND Looper
        pluginProvider.reverbPlugin.outputs["outputLeft"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.reverbPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.reverbPlugin.outputs["outputLeft"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.reverbPlugin.outputs["outputRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)

        // Delay wet outputs → Stereo sum AND Looper input
        pluginProvider.delayPlugin.outputs["wetLeft"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.delayPlugin.outputs["wetRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.delayPlugin.outputs["wet2Left"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.delayPlugin.outputs["wet2Right"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.delayPlugin.outputs["wetLeft"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.delayPlugin.outputs["wetRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        pluginProvider.delayPlugin.outputs["wet2Left"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.delayPlugin.outputs["wet2Right"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)

        // Bender audio effects (tension/spring sounds) → Stereo sum (mono to both channels) AND Looper
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        
        // Bender → Delay (Send)
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.delayPlugin.inputs["inputLeft"]!!)
        pluginProvider.benderPlugin.outputs["audioOutput"]?.connect(pluginProvider.delayPlugin.inputs["inputRight"]!!)
        
        // Per-String Bender audio effects (tension/spring sounds) → Stereo sum AND Looper
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        
        // Looper Output -> Stereo Sum (Stereo: Left to Left, Right to Right)
        pluginProvider.looperPlugin.outputs["output"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.looperPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)

        // Warps Meta-Modulator -> Stereo Sum AND Looper
        voiceSumLeft.output.connect(pluginProvider.warpsPlugin.inputs["inputLeft"]!!)
        voiceSumRight.output.connect(pluginProvider.warpsPlugin.inputs["inputRight"]!!)
        pluginProvider.warpsPlugin.outputs["output"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.warpsPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.warpsPlugin.outputs["output"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.warpsPlugin.outputs["outputRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
        
        // Warps → Delay (Send)
        pluginProvider.warpsPlugin.outputs["output"]?.connect(pluginProvider.delayPlugin.inputs["inputLeft"]!!)
        pluginProvider.warpsPlugin.outputs["outputRight"]?.connect(pluginProvider.delayPlugin.inputs["inputRight"]!!)

        // Flux Clock Wiring (Sync GlobalClock to Flux)
        globalTempo.getClockOutput().connect(pluginProvider.fluxPlugin.inputs["clock"]!!)

        // Stereo outputs → LineOut
        pluginProvider.stereoPlugin.outputs["lineOutLeft"]?.connect(audioEngine.lineOutLeft)
        pluginProvider.stereoPlugin.outputs["lineOutRight"]?.connect(audioEngine.lineOutRight)
        
        // Resonator output goes to Distortion input (resonator path continues)
        pluginProvider.resonatorPlugin.outputs["outputLeft"]!!.connect(pluginProvider.distortionPlugin.inputs["inputLeft"]!!)
        pluginProvider.resonatorPlugin.outputs["outputRight"]!!.connect(pluginProvider.distortionPlugin.inputs["inputRight"]!!)
    }
    
    private fun wireDrums() {
        // Drum routing (Bypass Chain switchable)
        pluginProvider.drumPlugin.outputs["outputLeft"]?.connect(drumChainGainL.inputA)
        pluginProvider.drumPlugin.outputs["outputRight"]?.connect(drumChainGainR.inputA)
        pluginProvider.drumPlugin.outputs["outputLeft"]?.connect(drumDirectGainL.inputA)
        pluginProvider.drumPlugin.outputs["outputRight"]?.connect(drumDirectGainR.inputA)

        // Path 1: To full chain (Resonator -> Distortion -> Delay)
        drumChainGainL.output.connect(pluginProvider.resonatorPlugin.inputs["drumLeft"]!!)
        drumChainGainR.output.connect(pluginProvider.resonatorPlugin.inputs["drumRight"]!!)
        drumChainGainL.output.connect(pluginProvider.resonatorPlugin.inputs["fullDrumLeft"]!!)
        drumChainGainR.output.connect(pluginProvider.resonatorPlugin.inputs["fullDrumRight"]!!)

        // Path 2: Direct to output (Stereo Sum + Looper) -> VIA Dedicated Resonator -> Dedicated Limiter (Distortion)
        
        // Connect Gains to Direct Splits
        drumDirectGainL.output.connect(drumDirectResoDryGainL.inputA)
        drumDirectGainR.output.connect(drumDirectResoDryGainR.inputA)
        drumDirectGainL.output.connect(drumDirectResonator.input)
        drumDirectGainR.output.connect(drumDirectResonator.input)
        
        // Resonator Output Routing
        drumDirectResonator.output.connect(drumDirectResoWetGainL.inputA)
        drumDirectResonator.output.connect(drumDirectResoWetGainR.inputA)

        // Sum Dry and Wet
        drumDirectResoDryGainL.output.connect(drumDirectResoSumL.inputA)
        drumDirectResoWetGainL.output.connect(drumDirectResoSumL.inputB)
        drumDirectResoDryGainR.output.connect(drumDirectResoSumR.inputA)
        drumDirectResoWetGainR.output.connect(drumDirectResoSumR.inputB)

        // Sums -> Limiters
        drumDirectResoSumL.output.connect(drumDirectLimiterL.input)
        drumDirectResoSumR.output.connect(drumDirectLimiterR.input)
        
        drumDirectLimiterL.output.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        drumDirectLimiterR.output.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        drumDirectLimiterL.output.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        drumDirectLimiterR.output.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)
    }
    
    private fun wireVoices(voiceManager: DspVoiceManager) {
        // Wire voices to audio paths
        voiceManager.voices.forEachIndexed { index, voice ->
            // VIBRATO → Voice frequency modulation
            pluginProvider.vibratoPlugin.outputs["output"]?.connect(voice.vibratoInput)
            voice.vibratoDepth.set(1.0)

            // GLOBAL BENDER → Voice pitch bend modulation
            pluginProvider.benderPlugin.outputs["pitchOutput"]?.connect(voice.benderInput)
            
            // PER-STRING BENDER
            if (index < 8) {
                pluginProvider.perStringBenderPlugin.outputs["voiceBend$index"]?.connect(voice.benderInput)
            }
        }
        
        // Per-String Bender audio effects (tension/spring sounds) → Stereo sum
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)

        // Wire per-voice panning: Voice → PanL/R → voiceSum (for Grains) AND Resonator
        voiceManager.voices.forEachIndexed { index, voice ->
            // Voice audio goes to pan gain inputs
            voice.output.connect(pluginProvider.stereoPlugin.getVoicePanInputLeft(index))
            voice.output.connect(pluginProvider.stereoPlugin.getVoicePanInputRight(index))
            
            // Panned audio goes to voice sum buses (feeds Grains in parallel)
            pluginProvider.stereoPlugin.getVoicePanOutputLeft(index).connect(voiceSumLeft.input)
            pluginProvider.stereoPlugin.getVoicePanOutputRight(index).connect(voiceSumRight.input)
            
            // Panned audio ALSO goes to Resonator gated inputs (excitation) - parallel path
            pluginProvider.stereoPlugin.getVoicePanOutputLeft(index).connect(pluginProvider.resonatorPlugin.inputs["synthLeft"]!!)
            pluginProvider.stereoPlugin.getVoicePanOutputRight(index).connect(pluginProvider.resonatorPlugin.inputs["synthRight"]!!)
            
            // Panned audio ALSO goes to Resonator non-gated inputs (full dry path)
            pluginProvider.stereoPlugin.getVoicePanOutputLeft(index).connect(pluginProvider.resonatorPlugin.inputs["fullSynthLeft"]!!)
            pluginProvider.stereoPlugin.getVoicePanOutputRight(index).connect(pluginProvider.resonatorPlugin.inputs["fullSynthRight"]!!)

            // REPL Voices (8-11) go to separate REPL bus
            if (index >= 8) {
                pluginProvider.stereoPlugin.getVoicePanOutputLeft(index).connect(replSumLeft.input)
                pluginProvider.stereoPlugin.getVoicePanOutputRight(index).connect(replSumRight.input)
            }
        }
    }
    
    private fun initDrumDirectResonator() {
        drumDirectResonator.setEnabled(true)
        drumDirectResonator.setMode(0) 
        drumDirectResoDryGainL.inputB.set(1.0)
        drumDirectResoDryGainR.inputB.set(1.0)
        drumDirectResoWetGainL.inputB.set(0.0)
        drumDirectResoWetGainR.inputB.set(0.0)
    }
}
