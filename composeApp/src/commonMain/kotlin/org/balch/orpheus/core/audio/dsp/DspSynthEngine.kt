package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import kotlin.math.pow

/**
 * Shared implementation of SynthEngine using DSP primitive interfaces.
 * All audio routing logic is platform-independent.
 * 
 * Uses a plugin architecture where processing modules are injected and wired together.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSynthEngine(
    private val audioEngine: AudioEngine,
    private val pluginProvider: DspPluginProvider,
    private val dispatcherProvider: DispatcherProvider,
) : SynthEngine {

    private val log = logging("DspSynthEngine")

    // 8 Voices with pitch ranges (0.5=bass, 1.0=mid, 2.0=high)
    private val voices = listOf(
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        DspVoice(audioEngine, pitchMultiplier = 0.5),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 2.0),
        DspVoice(audioEngine, pitchMultiplier = 2.0),
        // REPL Voices (Quad 3)
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0),
        DspVoice(audioEngine, pitchMultiplier = 1.0)
    )

    // TOTAL FB: Output → LFO Frequency Modulation
    private val totalFbGain = audioEngine.createMultiply()
    
    // Voice sum buses (for feeding into Grains before resonator)
    private val voiceSumLeft = audioEngine.createPassThrough()
    private val voiceSumRight = audioEngine.createPassThrough()

    // State caches
    private val quadPitchOffsets = DoubleArray(3) { 0.5 }
    private val voiceTuneCache = DoubleArray(12) { 0.5 }

    // Reactive monitoring flows
    private val _peakFlow = MutableStateFlow(0f)
    override val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(0f)
    override val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()

    private val _voiceLevelsFlow = MutableStateFlow(FloatArray(12))
    override val voiceLevelsFlow: StateFlow<FloatArray> = _voiceLevelsFlow.asStateFlow()

    private val _lfoOutputFlow = MutableStateFlow(0f)
    override val lfoOutputFlow: StateFlow<Float> = _lfoOutputFlow.asStateFlow()

    private val _masterLevelFlow = MutableStateFlow(0f)
    override val masterLevelFlow: StateFlow<Float> = _masterLevelFlow.asStateFlow()

    private val _driveFlow = MutableStateFlow(0f)
    override val driveFlow: StateFlow<Float> = _driveFlow.asStateFlow()

    private val _distortionMixFlow = MutableStateFlow(0f)
    override val distortionMixFlow: StateFlow<Float> = _distortionMixFlow.asStateFlow()

    private val _delayMixFlow = MutableStateFlow(0f)
    override val delayMixFlow: StateFlow<Float> = _delayMixFlow.asStateFlow()

    private val _delayFeedbackFlow = MutableStateFlow(0f)
    override val delayFeedbackFlow: StateFlow<Float> = _delayFeedbackFlow.asStateFlow()

    private val _quadPitchFlow = MutableStateFlow(FloatArray(3) { 0.5f })
    override val quadPitchFlow: StateFlow<FloatArray> = _quadPitchFlow.asStateFlow()

    private val _quadHoldFlow = MutableStateFlow(FloatArray(3))
    override val quadHoldFlow: StateFlow<FloatArray> = _quadHoldFlow.asStateFlow()

    private val _bendFlow = MutableStateFlow(0f)
    override val bendFlow: StateFlow<Float> = _bendFlow.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    // Audio-Rate Automation System
    // ═══════════════════════════════════════════════════════════
    private data class AutomationSetup(
        val player: AutomationPlayer,
        val scaler: MultiplyAdd,
        val targets: List<AudioInput>,
        val restoreManualValue: () -> Unit
    )
    
    private val automationSetups = mutableMapOf<String, AutomationSetup>()
    private val activeAutomations = mutableSetOf<String>()

    // State Caches
    private val _voiceTune = FloatArray(12) { 0.5f }
    private val _voiceFmDepth = FloatArray(12)
    private val _voiceEnvelopeSpeed = FloatArray(12)
    private val _pairSharpness = FloatArray(6)
    private val _duoModSource = Array(6) { ModSource.OFF }
    private val _quadPitch = FloatArray(3) { 0.5f }
    private val _quadHold = FloatArray(3)
    private val _quadVolume = FloatArray(3) { 1.0f }  // Default to full volume
    private var _fmStructureCrossQuad = false
    private var _totalFeedback = 0.0f
    private var _voiceCoupling = 0.0f
    private var _stereoMode = StereoMode.VOICE_PAN

    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
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
        
        // Initialize all plugins (sets up internal wiring)
        pluginProvider.plugins.forEach { it.initialize() }

        // TOTAL FB: StereoPlugin.peak → scaled → HyperLfo.feedbackInput
        pluginProvider.stereoPlugin.outputs["peakOutput"]?.connect(totalFbGain.inputA)
        totalFbGain.inputB.set(0.0) // Default: no feedback
        totalFbGain.output.connect(pluginProvider.hyperLfo.feedbackInput)

        // ═══════════════════════════════════════════════════════════
        // INTER-PLUGIN WIRING
        // 
        // Signal Flow (Grains PARALLEL to Resonator):
        //   Voices → Pan → voiceSum ─┬→ Grains ──────────────────→ Stereo Sum
        //                            └→ Resonator → Distortion ──→ Stereo Sum
        //   Drums → Resonator ↗ (bypasses Grains entirely)
        // ═══════════════════════════════════════════════════════════

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

        // Distortion → Stereo Sum (resonator path output) AND Looper input
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)
        pluginProvider.distortionPlugin.outputs["outputLeft"]?.connect(pluginProvider.looperPlugin.inputs["inputLeft"]!!)
        pluginProvider.distortionPlugin.outputs["outputRight"]?.connect(pluginProvider.looperPlugin.inputs["inputRight"]!!)

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

        // Stereo outputs → LineOut
        pluginProvider.stereoPlugin.outputs["lineOutLeft"]?.connect(audioEngine.lineOutLeft)
        pluginProvider.stereoPlugin.outputs["lineOutRight"]?.connect(audioEngine.lineOutRight)

        // Drum outputs → Resonator ONLY (drums bypass Grains)
        pluginProvider.drumPlugin.outputs["outputLeft"]?.connect(pluginProvider.resonatorPlugin.inputs["drumLeft"]!!)
        pluginProvider.drumPlugin.outputs["outputRight"]?.connect(pluginProvider.resonatorPlugin.inputs["drumRight"]!!)
        
        // Drum outputs → Resonator non-gated inputs (full dry path)
        pluginProvider.drumPlugin.outputs["outputLeft"]?.connect(pluginProvider.resonatorPlugin.inputs["fullDrumLeft"]!!)
        pluginProvider.drumPlugin.outputs["outputRight"]?.connect(pluginProvider.resonatorPlugin.inputs["fullDrumRight"]!!)

        // Wire voices to audio paths
        voices.forEachIndexed { index, voice ->
            // VOICES → DELAYS (wet path)
            voice.output.connect(pluginProvider.delayPlugin.inputs["input"]!!)

            // VIBRATO → Voice frequency modulation
            pluginProvider.vibratoPlugin.outputs["output"]?.connect(voice.vibratoInput)
            voice.vibratoDepth.set(1.0)

            // GLOBAL BENDER → Voice pitch bend modulation (for fader bender)
            pluginProvider.benderPlugin.outputs["pitchOutput"]?.connect(voice.benderInput)
            
            // PER-STRING BENDER → Voice pitch bend modulation (for string bender)
            // Only wire first 8 voices (quad 0 and 1 = 4 strings * 2 voices)
            if (index < 8) {
                pluginProvider.perStringBenderPlugin.outputs["voiceBend$index"]?.connect(voice.benderInput)
            }

            // COUPLING default depth
            voice.couplingDepth.set(0.0)
        }
        
        // Per-String Bender audio effects (tension/spring sounds) → Stereo sum
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputLeft"]!!)
        pluginProvider.perStringBenderPlugin.outputs["audioOutput"]?.connect(pluginProvider.stereoPlugin.inputs["dryInputRight"]!!)

        // Wire voice coupling
        for (pairIndex in 0 until 6) {
            val voiceA = voices[pairIndex * 2]
            val voiceB = voices[pairIndex * 2 + 1]
            voiceA.envelopeOutput.connect(voiceB.couplingInput)
            voiceB.envelopeOutput.connect(voiceA.couplingInput)
        }

        // Wire per-voice panning: Voice → PanL/R → voiceSum (for Grains) AND Resonator
        voices.forEachIndexed { index, voice ->
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
        }

        // Resonator output goes to Distortion input (resonator path continues)
        pluginProvider.resonatorPlugin.outputs["outputLeft"]!!.connect(pluginProvider.distortionPlugin.inputs["inputLeft"]!!)
        pluginProvider.resonatorPlugin.outputs["outputRight"]!!.connect(pluginProvider.distortionPlugin.inputs["inputRight"]!!)

        // Wire Radio outputs to Stereo sum (if radio plugin is present)
        // Note: For now, we connect all existing radio channels to the stereo mixer.
        // As new channels are added at runtime, they will need to be wired.
        // For V1, we'll implement a 'm_wireRadioChannel' helper.


        // ═══════════════════════════════════════════════════════════
        // Automation Setup
        // ═══════════════════════════════════════════════════════════
        
        fun setupAutomation(
            id: String,
            targets: List<AudioInput>,
            scale: Double,
            offset: Double,
            restoreManualValue: () -> Unit
        ) {
            val player = audioEngine.createAutomationPlayer()
            val scaler = audioEngine.createMultiplyAdd()
            scaler.inputB.set(scale)
            scaler.inputC.set(offset)
            player.output.connect(scaler.inputA)
            
            automationSetups[id] = AutomationSetup(player, scaler, targets, restoreManualValue)
            audioEngine.addUnit(player)
            audioEngine.addUnit(scaler)
        }

        // LFO Frequencies
        setupAutomation("hyper_lfo_a", listOf(pluginProvider.hyperLfo.frequencyA), 10.0, 0.01) { setHyperLfoFreq(0, getHyperLfoFreq(0)) }
        setupAutomation("hyper_lfo_b", listOf(pluginProvider.hyperLfo.frequencyB), 10.0, 0.01) { setHyperLfoFreq(1, getHyperLfoFreq(1)) }

        // Delay Times
        setupAutomation("delay_time_1", listOf(pluginProvider.delayPlugin.delay1TimeRampInput), 1.99, 0.01) { setDelayTime(0, getDelayTime(0)) }
        setupAutomation("delay_time_2", listOf(pluginProvider.delayPlugin.delay2TimeRampInput), 1.99, 0.01) { setDelayTime(1, getDelayTime(1)) }

        // Delay Mod Depths
        setupAutomation("delay_mod_1", listOf(pluginProvider.delayPlugin.delay1ModDepthRampInput), 0.1, 0.0) { setDelayModDepth(0, getDelayModDepth(0)) }
        setupAutomation("delay_mod_2", listOf(pluginProvider.delayPlugin.delay2ModDepthRampInput), 0.1, 0.0) { setDelayModDepth(1, getDelayModDepth(1)) }

        // Delay Feedback
        setupAutomation("delay_feedback", listOf(pluginProvider.delayPlugin.delay1FeedbackInput, pluginProvider.delayPlugin.delay2FeedbackInput), 0.95, 0.0) { setDelayFeedback(getDelayFeedback()) }

        // Vibrato
        setupAutomation("vibrato", listOf(), 20.0, 0.0) { setVibrato(getVibrato()) }

        // Master Volume
        setupAutomation("master_volume", listOf(pluginProvider.stereoPlugin.masterGainLeftInput, pluginProvider.stereoPlugin.masterGainRightInput), 1.0, 0.0) { setMasterVolume(getMasterVolume()) }

        // Drive
        setupAutomation("drive", listOf(pluginProvider.distortionPlugin.limiterLeftDrive, pluginProvider.distortionPlugin.limiterRightDrive), 14.0, 1.0) { setDrive(getDrive()) }

        // Delay Mix - complex with wet and dry scalers
        run {
            val player = audioEngine.createAutomationPlayer()
            val wetScaler = audioEngine.createMultiplyAdd()
            val dryScaler = audioEngine.createMultiplyAdd()
            
            wetScaler.inputB.set(1.0)
            wetScaler.inputC.set(0.0)
            dryScaler.inputB.set(-1.0)
            dryScaler.inputC.set(1.0)
            
            player.output.connect(wetScaler.inputA)
            player.output.connect(dryScaler.inputA)
            
            val wetTargets = listOf(
                pluginProvider.delayPlugin.delay1WetLeftGain, pluginProvider.delayPlugin.delay1WetRightGain,
                pluginProvider.delayPlugin.delay2WetLeftGain, pluginProvider.delayPlugin.delay2WetRightGain
            )
            val dryTargets = listOf(pluginProvider.distortionPlugin.dryGainLeftInput, pluginProvider.distortionPlugin.dryGainRightInput)
            
            automationSetups["delay_mix"] = AutomationSetup(player, wetScaler, wetTargets + dryTargets) { setDelayMix(getDelayMix()) }
            automationSetups["delay_mix_dry"] = AutomationSetup(player, dryScaler, dryTargets) {}
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(wetScaler)
            audioEngine.addUnit(dryScaler)
        }

        // Distortion Mix
        run {
            val player = audioEngine.createAutomationPlayer()
            val distScaler = audioEngine.createMultiplyAdd()
            val cleanScaler = audioEngine.createMultiplyAdd()
            
            distScaler.inputB.set(1.0)
            distScaler.inputC.set(0.0)
            cleanScaler.inputB.set(-1.0)
            cleanScaler.inputC.set(1.0)
            
            player.output.connect(distScaler.inputA)
            player.output.connect(cleanScaler.inputA)
            
            val distTargets = listOf(pluginProvider.distortionPlugin.distortedPathLeftGain, pluginProvider.distortionPlugin.distortedPathRightGain)
            val cleanTargets = listOf(pluginProvider.distortionPlugin.cleanPathLeftGain, pluginProvider.distortionPlugin.cleanPathRightGain)
            
            automationSetups["distortion_mix"] = AutomationSetup(player, distScaler, distTargets + cleanTargets) { setDistortionMix(getDistortionMix()) }
            automationSetups["distortion_mix_clean"] = AutomationSetup(player, cleanScaler, cleanTargets) {}
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(distScaler)
            audioEngine.addUnit(cleanScaler)
        }

        // Set defaults
        setDelayMix(0.5f)

        // Voice Automation
        for (i in 0 until 12) {
            // Gate automation (0.0 to 1.0)
            setupAutomation(
                "voice_gate_$i",
                listOf(voices[i].gate),
                1.0,
                0.0
            ) { setVoiceGate(i, false) }

            // Frequency automation (Hz) - uses directFrequency to bypass pitchScaler
            // This ensures Notes play at exact Hz without pitch multiplier interference
            setupAutomation(
                "voice_freq_$i",
                listOf(voices[i].directFrequency),  // Bypass pitchScaler!
                1.0,
                0.0
            ) {
                // Restore: clear direct frequency and let pitchScaler take over again
                voices[i].directFrequency.disconnectAll()
                voices[i].directFrequency.set(0.0)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SynthEngine Implementation
    // ═══════════════════════════════════════════════════════════

    private var monitoringJob: Job? = null

    override fun start() {
        if (audioEngine.isRunning) return
        log.debug { "Starting Shared Audio Engine..." }
        audioEngine.start()

        monitoringJob = monitoringScope.launch(dispatcherProvider.io) {
            val voiceLevels = FloatArray(12)
            while (isActive) {
                val currentPeak = pluginProvider.stereoPlugin.getPeak()
                _peakFlow.value = currentPeak
                _cpuLoadFlow.value = audioEngine.getCpuLoad()

                var voiceSum = 0f
                for (i in 0 until 12) {
                    val level = voices[i].getCurrentLevel()
                    voiceLevels[i] = level
                    voiceSum += level
                }
                _voiceLevelsFlow.value = voiceLevels.copyOf()
                _lfoOutputFlow.value = pluginProvider.hyperLfo.getCurrentValue()
                
                val computedMaster = (voiceSum / 12f).coerceIn(0f, 1f)
                _masterLevelFlow.value = maxOf(currentPeak.coerceIn(0f, 1f), computedMaster)

                delay(33) // ~30fps
            }
        }
        log.debug { "Audio Engine Started" }
    }

    override fun getCurrentTime(): Double = audioEngine.getCurrentTime()

    override fun stop() {
        log.debug { "Stopping Audio Engine..." }
        monitoringJob?.cancel()
        monitoringJob = null
        audioEngine.stop()
        log.debug { "Audio Engine Stopped" }
    }

    // Delay delegations
    override fun setDelayTime(index: Int, time: Float) = pluginProvider.delayPlugin.setTime(index, time)
    override fun setDelayFeedback(amount: Float) {
        pluginProvider.delayPlugin.setFeedback(amount)
        _delayFeedbackFlow.value = amount
    }
    override fun setDelayMix(amount: Float) {
        pluginProvider.delayPlugin.setMix(amount)
        pluginProvider.distortionPlugin.setDryLevel(1.0f - amount)
        _delayMixFlow.value = amount
    }
    override fun setDelayModDepth(index: Int, amount: Float) = pluginProvider.delayPlugin.setModDepth(index, amount)
    override fun setDelayModSource(index: Int, isLfo: Boolean) = pluginProvider.delayPlugin.setModSource(index, isLfo)
    override fun setDelayLfoWaveform(isTriangle: Boolean) = pluginProvider.hyperLfo.setTriangleMode(isTriangle)
    @Deprecated("Use granular setDelayTime/Feedback instead")
    override fun setDelay(time: Float, feedback: Float) {
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }
    override fun getDelayTime(index: Int): Float = pluginProvider.delayPlugin.getTime(index)
    override fun getDelayFeedback(): Float = pluginProvider.delayPlugin.getFeedback()
    override fun getDelayMix(): Float = pluginProvider.delayPlugin.getMix()
    override fun getDelayModDepth(index: Int): Float = pluginProvider.delayPlugin.getModDepth(index)
    override fun getDelayModSourceIsLfo(index: Int): Boolean = pluginProvider.delayPlugin.getModSourceIsLfo(index)
    override fun getDelayLfoWaveformIsTriangle(): Boolean = true // Default to triangle

    // Beats Mix delegation
    override fun setBeatsMix(mix: Float) {
        pluginProvider.drumPlugin.setMix(mix)
    }
    override fun getBeatsMix(): Float = pluginProvider.drumPlugin.getMix()
    
    // Looper delegations
    override fun setLooperRecord(recording: Boolean) {
        pluginProvider.looperPlugin.setRecording(recording)
    }
    
    override fun setLooperPlay(playing: Boolean) {
        pluginProvider.looperPlugin.setPlaying(playing)
    }
    
    override fun setLooperOverdub(overdub: Boolean) {
        // Not implemented (needs Feedback loop in Looper)
    }
    
    override fun clearLooper() {
        pluginProvider.looperPlugin.clear()
    }
    
    override fun getLooperPosition(): Float = pluginProvider.looperPlugin.getPosition()
    override fun getLooperDuration(): Double = pluginProvider.looperPlugin.getLoopDuration()

    // HyperLFO delegations
    override fun setHyperLfoFreq(index: Int, frequency: Float) = pluginProvider.hyperLfo.setFreq(index, frequency)
    override fun setHyperLfoMode(mode: Int) = pluginProvider.hyperLfo.setMode(mode)
    override fun setHyperLfoLink(active: Boolean) = pluginProvider.hyperLfo.setLink(active)
    override fun getHyperLfoFreq(index: Int): Float = pluginProvider.hyperLfo.getFreq(index)
    override fun getHyperLfoMode(): Int = pluginProvider.hyperLfo.getMode()
    override fun getHyperLfoLink(): Boolean = pluginProvider.hyperLfo.getLink()

    // Distortion delegations
    override fun setDrive(amount: Float) {
        pluginProvider.distortionPlugin.setDrive(amount)
        _driveFlow.value = amount
    }
    override fun setDistortionMix(amount: Float) {
        pluginProvider.distortionPlugin.setMix(amount)
        _distortionMixFlow.value = amount
    }
    override fun getDrive(): Float = pluginProvider.distortionPlugin.getDrive()
    override fun getDistortionMix(): Float = pluginProvider.distortionPlugin.getMix()

    // Stereo delegations
    override fun setMasterVolume(amount: Float) = pluginProvider.stereoPlugin.setMasterVolume(amount)
    override fun getMasterVolume(): Float = pluginProvider.stereoPlugin.getMasterVolume()
    override fun setVoicePan(index: Int, pan: Float) = pluginProvider.stereoPlugin.setVoicePan(index, pan)
    override fun getVoicePan(index: Int): Float = pluginProvider.stereoPlugin.getVoicePan(index)
    override fun setMasterPan(pan: Float) = pluginProvider.stereoPlugin.setMasterPan(pan)
    override fun getMasterPan(): Float = pluginProvider.stereoPlugin.getMasterPan()
    override fun setStereoMode(mode: StereoMode) {
        _stereoMode = mode
        pluginProvider.delayPlugin.setStereoMode(mode == StereoMode.STEREO_DELAYS)
    }
    override fun getStereoMode(): StereoMode = _stereoMode

    // Vibrato delegation
    override fun setVibrato(amount: Float) = pluginProvider.vibratoPlugin.setDepth(amount)
    override fun getVibrato(): Float = pluginProvider.vibratoPlugin.getDepth()
    
    // Bender delegation
    override fun setBend(amount: Float) {
        pluginProvider.benderPlugin.setBend(amount)
        _bendFlow.value = amount
    }
    override fun getBend(): Float = pluginProvider.benderPlugin.getBend()
    
    // Per-String Bender delegation
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        if (pluginProvider.perStringBenderPlugin.setStringBend(stringIndex, bendAmount, voiceMix)) {
            // Plugin requests voice trigger
            val voiceA = stringIndex * 2
            val voiceB = stringIndex * 2 + 1
            // Ensure we don't go out of bounds (though strings 0-3 map to voices 0-7 so its safe)
            if (voiceA < 12) setVoiceGate(voiceA, true)
            if (voiceB < 12) setVoiceGate(voiceB, true)
        }
    }
    
    override fun releaseStringBend(stringIndex: Int): Int {
        val (springDuration, shouldRelease) = pluginProvider.perStringBenderPlugin.releaseString(stringIndex)
        
        if (shouldRelease) {
             val voiceA = stringIndex * 2
             val voiceB = stringIndex * 2 + 1
             if (voiceA < 12) setVoiceGate(voiceA, false)
             if (voiceB < 12) setVoiceGate(voiceB, false)
        }
        
        return springDuration
    }
    
    // Slide Bar delegation
    override fun setSlideBar(yPosition: Float, xPosition: Float) {
        pluginProvider.perStringBenderPlugin.setSlideBar(yPosition, xPosition)
    }
    
    override fun releaseSlideBar() {
        pluginProvider.perStringBenderPlugin.releaseSlideBar()
    }
    
    override fun resetStringBenders() {
        pluginProvider.perStringBenderPlugin.resetAll()
    }

    // Voice controls (still managed locally)
    override fun setVoiceTune(index: Int, tune: Float) {
        _voiceTune[index] = tune
        voiceTuneCache[index] = tune.toDouble()
        updateVoiceFrequency(index)
    }

    private fun updateVoiceFrequency(index: Int) {
        val tune = voiceTuneCache[index]
        val quadIndex = index / 4
        val quadPitch = quadPitchOffsets[quadIndex]
        val baseFreq = 55.0 * 2.0.pow(tune * 4.0)
        val pitchMultiplier = 2.0.pow((quadPitch - 0.5) * 2.0)
        val finalFreq = baseFreq * pitchMultiplier
        voices[index].frequency.set(finalFreq)
        
        // Update string pluck frequency if this is the primary voice (A) of a pair
        // This ensures the string "pluck" sound matches the user's tuned notes (Issue 2)
        if (index % 2 == 0) {
            val stringIndex = index / 2
            pluginProvider.perStringBenderPlugin.setStringFrequency(stringIndex, finalFreq)
        }
    }

    override fun setVoiceGate(index: Int, active: Boolean) {
        voices[index].gate.set(if (active) 1.0 else 0.0)
    }

    override fun setVoiceFeedback(index: Int, amount: Float) { /* Not implemented */ }

    override fun setVoiceFmDepth(index: Int, amount: Float) {
        _voiceFmDepth[index] = amount
        voices[index].fmDepth.set(amount.toDouble())
    }

    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {
        _voiceEnvelopeSpeed[index] = speed
        voices[index].setEnvelopeSpeed(speed)
    }

    override fun setPairSharpness(pairIndex: Int, sharpness: Float) {
        _pairSharpness[pairIndex] = sharpness
        val voiceA = pairIndex * 2
        val voiceB = voiceA + 1
        voices[voiceA].sharpness.set(sharpness.toDouble())
        voices[voiceB].sharpness.set(sharpness.toDouble())
    }

    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {
        pluginProvider.drumPlugin.trigger(type, accent, frequency, tone, decay, p4, p5)
    }

    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {
        pluginProvider.drumPlugin.setParameters(type, frequency, tone, decay, p4, p5)
    }

    override fun triggerDrum(type: Int, accent: Float) {
        pluginProvider.drumPlugin.trigger(type, accent)
    }

    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        _quadPitch[quadIndex] = pitch
        _quadPitchFlow.value = _quadPitch.copyOf() // shallow copy is fine for primitive array
        quadPitchOffsets[quadIndex] = pitch.toDouble()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            updateVoiceFrequency(i)
        }
    }

    override fun setQuadHold(quadIndex: Int, amount: Float) {
        _quadHold[quadIndex] = amount
        _quadHoldFlow.value = _quadHold.copyOf()
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setHoldLevel(amount.toDouble())
        }
    }

    override fun setQuadVolume(quadIndex: Int, volume: Float) {
        _quadVolume[quadIndex] = volume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].setVolume(volume.toDouble())
        }
    }

    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) {
        _quadVolume[quadIndex] = targetVolume
        val startVoice = quadIndex * 4
        for (i in startVoice until startVoice + 4) {
            voices[i].fadeVolume(targetVolume.toDouble(), durationSeconds.toDouble())
        }
    }

    override fun setVoiceHold(index: Int, amount: Float) {
        voices[index].setHoldLevel(amount.toDouble())
    }

    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {
        // Apply wobble as a real-time VCA modulation
        // wobbleOffset is in [-1, 1], range determines max modulation depth
        // At wobbleOffset=0: multiplier=1.0 (no change)
        // At wobbleOffset=1 with range=0.3: multiplier=1.3 (30% boost)
        // At wobbleOffset=-1 with range=0.3: multiplier=0.7 (30% cut)
        val multiplier = 1.0 + (wobbleOffset * range)
        voices[index].setWobbleMultiplier(multiplier.coerceIn(0.0, 2.0))
    }

    override fun setDuoModSource(duoIndex: Int, source: ModSource) {
        _duoModSource[duoIndex] = source
        val voiceA = duoIndex * 2
        val voiceB = voiceA + 1

        voices[voiceA].modInput.disconnectAll()
        voices[voiceB].modInput.disconnectAll()

        when (source) {
            ModSource.OFF -> { }
            ModSource.LFO -> {
                pluginProvider.hyperLfo.output.connect(voices[voiceA].modInput)
                pluginProvider.hyperLfo.output.connect(voices[voiceB].modInput)
            }
            ModSource.VOICE_FM -> {
                if (_fmStructureCrossQuad) {
                    when (duoIndex) {
                        0 -> {
                            voices[6].output.connect(voices[voiceA].modInput)
                            voices[7].output.connect(voices[voiceB].modInput)
                        }
                        1 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        2 -> {
                            voices[2].output.connect(voices[voiceA].modInput)
                            voices[3].output.connect(voices[voiceB].modInput)
                        }
                        3 -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                        // Quad 3 (pairs 4, 5) default to simple peer connection
                        else -> {
                            voices[voiceA].output.connect(voices[voiceB].modInput)
                            voices[voiceB].output.connect(voices[voiceA].modInput)
                        }
                    }
                } else {
                    voices[voiceA].output.connect(voices[voiceB].modInput)
                    voices[voiceB].output.connect(voices[voiceA].modInput)
                }
            }
        }
    }

    override fun setFmStructure(crossQuad: Boolean) {
        _fmStructureCrossQuad = crossQuad
    }

    override fun setTotalFeedback(amount: Float) {
        _totalFeedback = amount
        val scaledAmount = amount * 20.0
        totalFbGain.inputB.set(scaledAmount)
    }

    override fun setVoiceCoupling(amount: Float) {
        _voiceCoupling = amount
        val depthHz = amount * 30.0
        voices.forEach { voice ->
            voice.couplingDepth.set(depthHz)
        }
    }

    // Test tone
    private var testOsc: SineOscillator? = null
    private var testGain: Multiply? = null
    
    override fun playTestTone(frequency: Float) {
        log.debug { "Playing test tone at ${frequency}Hz" }
        if (!audioEngine.isRunning) {
            audioEngine.start()
        }
        
        if (testOsc == null) {
            testOsc = audioEngine.createSineOscillator()
            testGain = audioEngine.createMultiply()
            audioEngine.addUnit(testOsc!!)
            audioEngine.addUnit(testGain!!)
            testOsc!!.output.connect(testGain!!.inputA)
            testGain!!.output.connect(audioEngine.lineOutLeft)
            testGain!!.output.connect(audioEngine.lineOutRight)
        }
        
        testOsc!!.frequency.set(frequency.toDouble())
        testOsc!!.amplitude.set(1.0)
        testGain!!.inputB.set(0.3)
    }

    override fun stopTestTone() {
        testGain?.inputB?.set(0.0)
    }

    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {
        val setup = automationSetups[controlId]
        if (setup == null) {
            log.debug { "Automation NOT FOUND: $controlId" }
            return
        }
        if (controlId.startsWith("voice_freq")) {
            log.debug { "Automation: $controlId, first value=${values.firstOrNull()} Hz, targets=${setup.targets.size}" }
        }
        
        val secondarySetup = when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]
            "distortion_mix" -> automationSetups["distortion_mix_clean"]
            else -> null
        }
        
        // Prepare voice automation by zeroing out the manual value
        // This ensures the automation adds to 0, rather than the manual setting.
        if (controlId.startsWith("voice_freq_")) {
            val index = controlId.removePrefix("voice_freq_").toIntOrNull()
            if (index != null && index in 0..11) {
                voices[index].frequency.set(0.0)
            }
        } else if (controlId.startsWith("voice_gate_")) {
            val index = controlId.removePrefix("voice_gate_").toIntOrNull()
            if (index != null && index in 0..11) {
                voices[index].gate.set(0.0)
            }
        }
        
        setup.targets.forEach { it.disconnectAll() }
        
        when (controlId) {
            "delay_mix" -> {
                listOf(pluginProvider.delayPlugin.delay1WetLeftGain, pluginProvider.delayPlugin.delay1WetRightGain, 
                       pluginProvider.delayPlugin.delay2WetLeftGain, pluginProvider.delayPlugin.delay2WetRightGain).forEach {
                    setup.scaler.output.connect(it)
                }
                secondarySetup?.let { secondary ->
                    listOf(pluginProvider.distortionPlugin.dryGainLeftInput, pluginProvider.distortionPlugin.dryGainRightInput).forEach {
                        secondary.scaler.output.connect(it)
                    }
                }
            }
            "distortion_mix" -> {
                listOf(pluginProvider.distortionPlugin.distortedPathLeftGain, pluginProvider.distortionPlugin.distortedPathRightGain).forEach {
                    setup.scaler.output.connect(it)
                }
                secondarySetup?.let { secondary ->
                    listOf(pluginProvider.distortionPlugin.cleanPathLeftGain, pluginProvider.distortionPlugin.cleanPathRightGain).forEach {
                        secondary.scaler.output.connect(it)
                    }
                }
            }
            else -> setup.targets.forEach { setup.scaler.output.connect(it) }
        }
        
        setup.player.setPath(times, values, count)
        setup.player.setDuration(duration)
        setup.player.setMode(mode)
        setup.player.play()
        activeAutomations.add(controlId)
    }

    override fun clearParameterAutomation(controlId: String) {
        val setup = automationSetups[controlId] ?: return
        setup.player.stop()
        setup.targets.forEach { it.disconnectAll() }
        
        // Disconnect secondary targets (mix/dry/clean)
        when (controlId) {
            "delay_mix" -> automationSetups["delay_mix_dry"]?.targets?.forEach { it.disconnectAll() }
            "distortion_mix" -> automationSetups["distortion_mix_clean"]?.targets?.forEach { it.disconnectAll() }
            else -> {}
        }
        
        setup.restoreManualValue()
        activeAutomations.remove(controlId)
    }

    override fun getPeak(): Float = pluginProvider.stereoPlugin.getPeak()
    override fun getCpuLoad(): Float = audioEngine.getCpuLoad()

    // Getters for State Saving
    override fun getVoiceTune(index: Int): Float = _voiceTune[index]
    override fun getVoiceFmDepth(index: Int): Float = _voiceFmDepth[index]
    override fun getVoiceEnvelopeSpeed(index: Int): Float = _voiceEnvelopeSpeed[index]
    override fun getPairSharpness(pairIndex: Int): Float = _pairSharpness[pairIndex]
    override fun getDuoModSource(duoIndex: Int): ModSource = _duoModSource[duoIndex]
    override fun getQuadPitch(quadIndex: Int): Float = _quadPitch[quadIndex]
    override fun getQuadHold(quadIndex: Int): Float = _quadHold[quadIndex]
    override fun getQuadVolume(quadIndex: Int): Float = _quadVolume[quadIndex]
    override fun getFmStructureCrossQuad(): Boolean = _fmStructureCrossQuad
    override fun getTotalFeedback(): Float = _totalFeedback
    override fun getVoiceCoupling(): Float = _voiceCoupling
    
    // ═══════════════════════════════════════════════════════════
    // Rings Resonator Delegation
    // ═══════════════════════════════════════════════════════════
    override fun setResonatorMode(mode: Int) = pluginProvider.resonatorPlugin.setMode(mode)
    override fun setResonatorTarget(target: Int) = pluginProvider.resonatorPlugin.setTarget(target)
    override fun setResonatorTargetMix(targetMix: Float) = pluginProvider.resonatorPlugin.setTargetMix(targetMix)
    override fun setResonatorStructure(value: Float) = pluginProvider.resonatorPlugin.setStructure(value)
    override fun setResonatorBrightness(value: Float) = pluginProvider.resonatorPlugin.setBrightness(value)
    override fun setResonatorDamping(value: Float) = pluginProvider.resonatorPlugin.setDamping(value)
    override fun setResonatorPosition(value: Float) = pluginProvider.resonatorPlugin.setPosition(value)
    override fun setResonatorMix(value: Float) = pluginProvider.resonatorPlugin.setMix(value)
    override fun strumResonator(frequency: Float) = pluginProvider.resonatorPlugin.strum(frequency)
    
    override fun getResonatorMode(): Int = pluginProvider.resonatorPlugin.getMode()
    override fun getResonatorTarget(): Int = pluginProvider.resonatorPlugin.getTarget()
    override fun getResonatorTargetMix(): Float = pluginProvider.resonatorPlugin.getTargetMix()
    override fun getResonatorStructure(): Float = pluginProvider.resonatorPlugin.getStructure()
    override fun getResonatorBrightness(): Float = pluginProvider.resonatorPlugin.getBrightness()
    override fun getResonatorDamping(): Float = pluginProvider.resonatorPlugin.getDamping()
    override fun getResonatorPosition(): Float = pluginProvider.resonatorPlugin.getPosition()
    override fun getResonatorMix(): Float = pluginProvider.resonatorPlugin.getMix()
    override fun getResonatorSnapBack(): Boolean = pluginProvider.resonatorPlugin.getSnapBack()
    override fun setResonatorSnapBack(enabled: Boolean) = pluginProvider.resonatorPlugin.setSnapBack(enabled)

    override fun getDrumFrequency(type: Int): Float = pluginProvider.drumPlugin.getFrequency(type)
    override fun getDrumTone(type: Int): Float = pluginProvider.drumPlugin.getTone(type)
    override fun getDrumDecay(type: Int): Float = pluginProvider.drumPlugin.getDecay(type)
    override fun getDrumP4(type: Int): Float = pluginProvider.drumPlugin.getP4(type)
    override fun getDrumP5(type: Int): Float = pluginProvider.drumPlugin.getP5(type)
    
    // Beat Sequencer Storage (State only)
    private var _beatsX = 0.5f
    private var _beatsY = 0.5f
    private val _beatsDensities = FloatArray(3) { 0.5f }
    private var _beatsBpm = 120f
    private var _beatsMode = 0
    private val _beatsEuclideanLengths = IntArray(3) { 16 }
    private var _beatsRandomness = 0f
    private var _beatsSwing = 0f

    override fun setBeatsX(x: Float) { _beatsX = x }
    override fun getBeatsX(): Float = _beatsX
    
    override fun setBeatsY(y: Float) { _beatsY = y }
    override fun getBeatsY(): Float = _beatsY
    
    override fun setBeatsDensity(index: Int, density: Float) { 
        if (index in 0..2) _beatsDensities[index] = density 
    }
    override fun getBeatsDensity(index: Int): Float = _beatsDensities.getOrElse(index) { 0.5f }
    
    override fun setBeatsBpm(bpm: Float) { _beatsBpm = bpm }
    override fun getBeatsBpm(): Float = _beatsBpm
    
    override fun setBeatsOutputMode(mode: Int) { _beatsMode = mode }
    override fun getBeatsOutputMode(): Int = _beatsMode
    
    override fun setBeatsEuclideanLength(index: Int, length: Int) {
        if (index in 0..2) _beatsEuclideanLengths[index] = length
    }
    override fun getBeatsEuclideanLength(index: Int): Int = _beatsEuclideanLengths.getOrElse(index) { 16 }
    
    override fun setBeatsRandomness(randomness: Float) { _beatsRandomness = randomness }
    override fun getBeatsRandomness(): Float = _beatsRandomness
    
    override fun setBeatsSwing(swing: Float) { _beatsSwing = swing }
    override fun getBeatsSwing(): Float = _beatsSwing

    // Grains Implementation
    private var _grainsPosition = 0f
    private var _grainsSize = 0f
    private var _grainsPitch = 0f
    private var _grainsDensity = 0f
    private var _grainsTexture = 0f
    private var _grainsDryWet = 0f
    private var _grainsFreeze = false
    
    override fun setGrainsPosition(value: Float) {
        _grainsPosition = value
        pluginProvider.grainsPlugin.inputs["position"]?.set(value.toDouble())
    }
    override fun setGrainsSize(value: Float) {
        _grainsSize = value
        pluginProvider.grainsPlugin.inputs["size"]?.set(value.toDouble())
    }
    override fun setGrainsPitch(value: Float) {
        _grainsPitch = value
        pluginProvider.grainsPlugin.inputs["pitch"]?.set(value.toDouble())
    }
    override fun setGrainsDensity(value: Float) {
        _grainsDensity = value
        pluginProvider.grainsPlugin.inputs["density"]?.set(value.toDouble())
    }
    override fun setGrainsTexture(value: Float) {
        _grainsTexture = value
        pluginProvider.grainsPlugin.inputs["texture"]?.set(value.toDouble())
    }
    override fun setGrainsDryWet(value: Float) {
        _grainsDryWet = value
        pluginProvider.grainsPlugin.inputs["dryWet"]?.set(value.toDouble())
    }
    override fun setGrainsFreeze(frozen: Boolean) {
        _grainsFreeze = frozen
        pluginProvider.grainsPlugin.inputs["freeze"]?.set(if (frozen) 1.0 else 0.0)
    }
    override fun setGrainsTrigger(trigger: Boolean) {
        pluginProvider.grainsPlugin.inputs["trigger"]?.set(if (trigger) 1.0 else 0.0)
    }
    
    private var _grainsMode: Int = 0 // 0=Granular (default)
    override fun setGrainsMode(mode: Int) {
        _grainsMode = mode
        pluginProvider.grainsPlugin.setMode(mode) // This will call CloudsUnit.setMode
    }
    
    override fun getGrainsPosition(): Float = _grainsPosition
    override fun getGrainsSize(): Float = _grainsSize
    override fun getGrainsPitch(): Float = _grainsPitch
    override fun getGrainsDensity(): Float = _grainsDensity
    override fun getGrainsTexture(): Float = _grainsTexture
    override fun getGrainsDryWet(): Float = _grainsDryWet
    override fun getGrainsFreeze(): Boolean = _grainsFreeze
    override fun getGrainsMode(): Int = _grainsMode

    // Warps Implementation
    private var _warpsAlgorithm = 0.0f
    private var _warpsTimbre = 0.5f
    private var _warpsLevel1 = 0.5f
    private var _warpsLevel2 = 0.5f
    private var _warpsCarrierSource = 0  // SYNTH
    private var _warpsModulatorSource = 1  // DRUMS
    private var _warpsMix = 0.5f

    override fun setWarpsAlgorithm(value: Float) {
        _warpsAlgorithm = value
        pluginProvider.warpsPlugin.setAlgorithm(value)
    }
    override fun setWarpsTimbre(value: Float) {
        _warpsTimbre = value
        pluginProvider.warpsPlugin.setTimbre(value)
    }
    override fun setWarpsLevel1(value: Float) {
        _warpsLevel1 = value
        pluginProvider.warpsPlugin.setLevel1(value)
    }
    override fun setWarpsLevel2(value: Float) {
        _warpsLevel2 = value
        pluginProvider.warpsPlugin.setLevel2(value)
    }

    override fun setWarpsCarrierSource(source: Int) {
        _warpsCarrierSource = source
        pluginProvider.warpsPlugin.disconnectCarrier()
        pluginProvider.warpsPlugin.setCarrierSource(source)

        // Connect the new source to carrier input
        getWarpsSourceOutput(source)?.first?.connect(pluginProvider.warpsPlugin.carrierRouteInput)

        // Also connect to dry path for proper dry/wet mix
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }

    override fun setWarpsModulatorSource(source: Int) {
        _warpsModulatorSource = source
        pluginProvider.warpsPlugin.disconnectModulator()
        pluginProvider.warpsPlugin.setModulatorSource(source)

        // Connect the new source to modulator input
        getWarpsSourceOutput(source)?.second?.connect(pluginProvider.warpsPlugin.modulatorRouteInput)

        // Also update dry path
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }

    override fun setWarpsMix(value: Float) {
        _warpsMix = value
        pluginProvider.warpsPlugin.setMix(value)
    }

    /**
     * Get audio outputs for a given WarpsSource ordinal.
     * Returns Pair<LeftOutput, RightOutput> for the source.
     *
     * For stereo sources (SYNTH, DRUMS, REPL), we return left output for the first
     * channel and right output for the second. This allows:
     * - Different sources on carrier/modulator: Normal stereo operation
     * - Same source on both: Carrier gets left, modulator gets right (stereo split)
     *
     * Sources:
     * - 0 = SYNTH: Main synth voices output (stereo)
     * - 1 = DRUMS: Drum machine output (stereo)
     * - 2 = REPL: Third quad voices 8-11, used for REPL-controlled notes (stereo)
     */
    private fun getWarpsSourceOutput(source: Int): Pair<AudioOutput, AudioOutput>? {
        return when (source) {
            0 -> Pair(voiceSumLeft.output, voiceSumRight.output)  // SYNTH
            1 -> pluginProvider.drumPlugin.outputs["outputLeft"]?.let { left ->
                pluginProvider.drumPlugin.outputs["outputRight"]?.let { right -> Pair(left, right) }
            }  // DRUMS
            2 -> {
                // REPL - voices 8-11 (third quad)
                // For REPL we use the same voiceSum since REPL voices contribute there
                Pair(voiceSumLeft.output, voiceSumRight.output)
            }
            else -> null
        }
    }

    override fun getWarpsAlgorithm(): Float = _warpsAlgorithm
    override fun getWarpsTimbre(): Float = _warpsTimbre
    override fun getWarpsLevel1(): Float = _warpsLevel1
    override fun getWarpsLevel2(): Float = _warpsLevel2
    override fun getWarpsCarrierSource(): Int = _warpsCarrierSource
    override fun getWarpsModulatorSource(): Int = _warpsModulatorSource
    override fun getWarpsMix(): Float = _warpsMix
}

