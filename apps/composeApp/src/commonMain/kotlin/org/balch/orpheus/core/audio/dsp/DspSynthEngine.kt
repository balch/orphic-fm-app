package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
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
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.debug.DebugViewModel.Companion.POLL_INTERVAL_MS
import org.balch.orpheus.plugins.bender.BenderPlugin
import org.balch.orpheus.plugins.bender.BenderSymbol
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.delay.DelaySymbol
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.distortion.DistortionSymbol
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.drum.DrumSymbol
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoSymbol
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.flux.FluxSymbol
import org.balch.orpheus.plugins.grains.GrainsPlugin
import org.balch.orpheus.plugins.grains.GrainsSymbol
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.resonator.ResonatorSymbol
import org.balch.orpheus.plugins.stereo.StereoPlugin
import org.balch.orpheus.plugins.stereo.StereoSymbol
import org.balch.orpheus.plugins.vibrato.VibratoPlugin
import org.balch.orpheus.plugins.vibrato.VibratoSymbol
import org.balch.orpheus.plugins.warps.WarpsPlugin
import org.balch.orpheus.plugins.warps.WarpsSymbol

/**
 * Shared implementation of SynthEngine using DSP primitive interfaces.
 * All audio routing logic is platform-independent.
 * 
 * Uses a component-based architecture:
 * - DspVoiceManager: Handles voice state and specialized voice logic
 * - DspWiringGraph: Handles static graph topology and wiring
 * - DspAutomationManager: Handles audio-rate parameter automation
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspSynthEngine @Inject constructor(
    private val audioEngine: AudioEngine,
    private val dspFactory: DspFactory,
    private val pluginProvider: DspPluginProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val globalTempo: GlobalTempo,
    private val voiceManager: DspVoiceManager,
    private val wiringGraph: DspWiringGraph,
    private val automationManager: DspAutomationManager
) : SynthEngine {

    private val log = logging("DspSynthEngine")

    // Plugin URI constants
    private companion object {
        const val GRAINS_URI = GrainsPlugin.URI
        const val FLUX_URI = FluxPlugin.URI
        const val WARPS_URI = WarpsPlugin.URI
        const val DUO_LFO_URI = DuoLfoPlugin.URI
        const val STEREO_URI = StereoPlugin.URI
        const val VIBRATO_URI = VibratoPlugin.URI
        const val BENDER_URI = BenderPlugin.URI
        const val DELAY_URI = DelayPlugin.URI
        const val DISTORTION_URI = DistortionPlugin.URI
        const val DRUM_URI = DrumPlugin.URI
        const val RESONATOR_URI = ResonatorPlugin.URI
    }

    // State
    private var fluxClockSource = 0 // 0=Internal, 1=LFO
    private var _drumsBypass = true
    private var _stereoMode = StereoMode.VOICE_PAN
    
    // Drum Sources
    private val drumTriggerSources = IntArray(3) { 0 }
    private val drumPitchSources = IntArray(3) { 0 }
    
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
    
    // Monitoring
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        voiceManager.initialize()
        wiringGraph.initialize(voiceManager)
        
        setupListeners()
        setupAutomation()
        
        // Initial defaults
        setDelayMix(0.5f)
        setDrumsBypass(true)
        setDrumTriggerSource(0, 1) // Kick -> T1
        setDrumTriggerSource(1, 2) // Snare -> T2
        setDrumTriggerSource(2, 3) // HiHat -> T3
    }
    
    private fun setupListeners() {
        // Register Voice Plugin Listener
        pluginProvider.voicePlugin.setListener(object : VoicePlugin.Listener {
            override fun onVoiceParamChange(index: Int, param: String, value: Any) {
                when (param) {
                    "tune" -> voiceManager.setVoiceTune(index, value as Float)
                    "mod_depth" -> voiceManager.setVoiceFmDepth(index, value as Float)
                    "env_speed" -> voiceManager.setVoiceEnvelopeSpeed(index, value as Float)
                    "sharpness" -> voiceManager.setPairSharpness(index, value as Float)
                    "duo_mod_source" -> voiceManager.setDuoModSource(index, ModSource.entries[value as Int])
                    "quad_pitch" -> {
                        voiceManager.setQuadPitch(index, value as Float)
                        _quadPitchFlow.value = voiceManager.getQuadPitchArrayCopy()
                    }
                    "quad_hold" -> {
                        voiceManager.setQuadHold(index, value as Float)
                        _quadHoldFlow.value = voiceManager.getQuadHoldArrayCopy()
                    }
                    "quad_volume" -> voiceManager.setQuadVolume(index, value as Float)
                    "quad_trigger_source" -> voiceManager.setQuadTriggerSource(index, value as Int)
                    "quad_pitch_source" -> voiceManager.setQuadPitchSource(index, value as Int)
                    "quad_env_trigger_mode" -> voiceManager.setQuadEnvelopeTriggerMode(index, value as Boolean)
                }
            }

            override fun onGlobalParamChange(param: String, value: Any) {
                when (param) {
                    "fm_structure" -> voiceManager.setFmStructure(value as Boolean)
                    "total_feedback" -> setTotalFeedback(value as Float)
                    "vibrato" -> setVibrato(value as Float)
                    "coupling" -> voiceManager.setVoiceCoupling(value as Float)
                }
            }
        })

        // Register Drum Plugin Listener
        pluginProvider.drumPlugin.setListener(object : DrumPlugin.Listener {
            override fun onRoutingChange(drumIndex: Int, type: String, value: Int) {
                if (type == "trigger") setDrumTriggerSource(drumIndex, value)
                if (type == "pitch") setDrumPitchSource(drumIndex, value)
            }
            override fun onBypassChange(bypass: Boolean) {
                setDrumsBypass(bypass)
            }
        })
    }
    
    private fun setupAutomation() {
        // LFO Frequencies
        automationManager.setupAutomation("hyper_lfo_a", listOf(pluginProvider.hyperLfo.frequencyA), 10.0, 0.01,
            { setHyperLfoFreq(0, getHyperLfoFreq(0)) }, {}) // No prepare needed
        automationManager.setupAutomation("hyper_lfo_b", listOf(pluginProvider.hyperLfo.frequencyB), 10.0, 0.01,
            { setHyperLfoFreq(1, getHyperLfoFreq(1)) }, {}) // No prepare needed

        // Delay Times
        automationManager.setupAutomation("delay_time_1", listOf(pluginProvider.delayPlugin.delay1TimeRampInput), 1.99, 0.01,
            { setDelayTime(0, getDelayTime(0)) }, {})
        automationManager.setupAutomation("delay_time_2", listOf(pluginProvider.delayPlugin.delay2TimeRampInput), 1.99, 0.01,
            { setDelayTime(1, getDelayTime(1)) }, {})

        // Delay Mod Depths
        automationManager.setupAutomation("delay_mod_1", listOf(pluginProvider.delayPlugin.delay1ModDepthRampInput), 0.1, 0.0,
            { setDelayModDepth(0, getDelayModDepth(0)) }, {})
        automationManager.setupAutomation("delay_mod_2", listOf(pluginProvider.delayPlugin.delay2ModDepthRampInput), 0.1, 0.0,
            { setDelayModDepth(1, getDelayModDepth(1)) }, {})

        // Delay Feedback
        automationManager.setupAutomation("delay_feedback", 
            listOf(pluginProvider.delayPlugin.delay1FeedbackInput, pluginProvider.delayPlugin.delay2FeedbackInput), 0.95, 0.0,
            { setDelayFeedback(getDelayFeedback()) }, {})

        // Vibrato
        automationManager.setupAutomation("vibrato", listOf(), 20.0, 0.0, 
            { setVibrato(getVibrato()) }, {})

        // Master Volume
        automationManager.setupAutomation("master_volume", 
            listOf(pluginProvider.stereoPlugin.masterGainLeftInput, pluginProvider.stereoPlugin.masterGainRightInput), 1.0, 0.0,
            { setMasterVolume(getMasterVolume()) }, {})

        // Drive
        // Note: accessing drumLimiters via wiringGraph
        automationManager.setupAutomation("drive", 
            listOf(pluginProvider.distortionPlugin.limiterLeftDrive, pluginProvider.distortionPlugin.limiterRightDrive, 
                   wiringGraph.drumDirectLimiterL.drive, wiringGraph.drumDirectLimiterR.drive), 14.0, 1.0,
            { setDrive(getDrive()) }, {})

        // Delay Mix
        run {
            val player = dspFactory.createAutomationPlayer()
            val wetScaler = dspFactory.createMultiplyAdd()
            val dryScaler = dspFactory.createMultiplyAdd()
            
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
            
            // Register custom setup
            automationManager.registerCustomAutomation("delay_mix", 
                DspAutomationManager.AutomationSetupWrapper(player, wetScaler, wetTargets + dryTargets, { setDelayMix(getDelayMix()) }))
            automationManager.registerCustomAutomation("delay_mix_dry", 
                DspAutomationManager.AutomationSetupWrapper(player, dryScaler, dryTargets, {}))
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(wetScaler)
            audioEngine.addUnit(dryScaler)
        }
        
        // Distortion Mix
        run {
            val player = dspFactory.createAutomationPlayer()
            val distScaler = dspFactory.createMultiplyAdd()
            val cleanScaler = dspFactory.createMultiplyAdd()
            
            distScaler.inputB.set(1.0)
            distScaler.inputC.set(0.0)
            cleanScaler.inputB.set(-1.0)
            cleanScaler.inputC.set(1.0)
            
            player.output.connect(distScaler.inputA)
            player.output.connect(cleanScaler.inputA)
            
            val distTargets = listOf(pluginProvider.distortionPlugin.distortedPathLeftGain, pluginProvider.distortionPlugin.distortedPathRightGain)
            val cleanTargets = listOf(pluginProvider.distortionPlugin.cleanPathLeftGain, pluginProvider.distortionPlugin.cleanPathRightGain)
            
            automationManager.registerCustomAutomation("distortion_mix", 
                DspAutomationManager.AutomationSetupWrapper(player, distScaler, distTargets + cleanTargets, { setDistortionMix(getDistortionMix()) }))
            automationManager.registerCustomAutomation("distortion_mix_clean", 
                DspAutomationManager.AutomationSetupWrapper(player, cleanScaler, cleanTargets, {}))
            
            audioEngine.addUnit(player)
            audioEngine.addUnit(distScaler)
            audioEngine.addUnit(cleanScaler)
        }
        
        // Voice Automation
        for (i in 0 until 12) {
            val voice = voiceManager.voices[i] // Access voices via manager
            
            // Gate automation (0.0 to 1.0)
            automationManager.setupAutomation(
                "voice_gate_$i",
                listOf(voice.gate),
                1.0, 0.0,
                { setVoiceGate(i, false) },
                { voice.gate.set(0.0) } // prepare: zero it out
            )

            // Frequency automation (Hz)
            automationManager.setupAutomation(
                "voice_freq_$i",
                listOf(voice.directFrequency),
                1.0, 0.0,
                {
                    // Restore: clear direct frequency
                    voice.directFrequency.disconnectAll()
                    voice.directFrequency.set(0.0)
                },
                {
                    // Prepare: zero it out
                    voice.directFrequency.set(0.0)
                }
            )
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
                    val level = voiceManager.voices[i].getCurrentLevel()
                    voiceLevels[i] = level
                    voiceSum += level
                }
                _voiceLevelsFlow.value = voiceLevels.copyOf()
                _lfoOutputFlow.value = pluginProvider.hyperLfo.getCurrentValue()
                
                val computedMaster = (voiceSum / 12f).coerceIn(0f, 1f)
                _masterLevelFlow.value = maxOf(currentPeak.coerceIn(0f, 1f), computedMaster)

                delay(POLL_INTERVAL_MS)
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

    // ═══════════════════════════════════════════════════════════
    // DELEGATIONS & FACADE METHODS
    // ═══════════════════════════════════════════════════════════

    override fun setDelayTime(index: Int, time: Float) {
        val symbol = if (index == 0) DelaySymbol.TIME_1.symbol else DelaySymbol.TIME_2.symbol
        setPluginPort(DELAY_URI, symbol, PortValue.FloatValue(time))
    }
    
    override fun setDelayFeedback(amount: Float) {
        setPluginPort(DELAY_URI, DelaySymbol.FEEDBACK.symbol, PortValue.FloatValue(amount))
        _delayFeedbackFlow.value = amount
    }
    
    override fun setDelayMix(amount: Float) {
        setPluginPort(DELAY_URI, DelaySymbol.MIX.symbol, PortValue.FloatValue(amount))
        setPluginPort(DISTORTION_URI, DistortionSymbol.DRY_LEVEL.symbol, PortValue.FloatValue(1.0f - amount))
        _delayMixFlow.value = amount
    }
    
    override fun setDelayModDepth(index: Int, amount: Float) {
        val symbol = if (index == 0) DelaySymbol.MOD_DEPTH_1.symbol else DelaySymbol.MOD_DEPTH_2.symbol
        setPluginPort(DELAY_URI, symbol, PortValue.FloatValue(amount))
    }
    
    override fun setDelayModSource(index: Int, isLfo: Boolean) = 
        setPluginPort(DELAY_URI, DelaySymbol.MOD_SOURCE.symbol, PortValue.BoolValue(isLfo)).let {}
    override fun setDelayLfoWaveform(isTriangle: Boolean) = 
        setPluginPort(DUO_LFO_URI, DuoLfoSymbol.TRIANGLE_MODE.symbol, PortValue.BoolValue(isTriangle)).let {}
    @Deprecated("Use granular setDelayTime/Feedback instead")
    override fun setDelay(time: Float, feedback: Float) {
        setDelayTime(0, time)
        setDelayTime(1, time)
        setDelayFeedback(feedback)
    }
    override fun getDelayTime(index: Int): Float {
        val symbol = if (index == 0) DelaySymbol.TIME_1.symbol else DelaySymbol.TIME_2.symbol
        return getPluginPort(DELAY_URI, symbol)?.asFloat() ?: 0.3f
    }
    override fun getDelayFeedback(): Float = 
        getPluginPort(DELAY_URI, DelaySymbol.FEEDBACK.symbol)?.asFloat() ?: 0.5f
    override fun getDelayMix(): Float = 
        getPluginPort(DELAY_URI, DelaySymbol.MIX.symbol)?.asFloat() ?: 0.5f
    override fun getDelayModDepth(index: Int): Float {
        val symbol = if (index == 0) DelaySymbol.MOD_DEPTH_1.symbol else DelaySymbol.MOD_DEPTH_2.symbol
        return getPluginPort(DELAY_URI, symbol)?.asFloat() ?: 0f
    }
    override fun getDelayModSourceIsLfo(index: Int): Boolean = 
        getPluginPort(DELAY_URI, DelaySymbol.MOD_SOURCE.symbol)?.asBoolean() ?: true
    override fun getDelayLfoWaveformIsTriangle(): Boolean = true

    // Beats Mix delegation
    override fun setBeatsMix(mix: Float) = 
        setPluginPort(DRUM_URI, DrumSymbol.MIX.symbol, PortValue.FloatValue(mix)).let {}
    override fun getBeatsMix(): Float = 
        getPluginPort(DRUM_URI, DrumSymbol.MIX.symbol)?.asFloat() ?: 0.7f
    
    override fun setDrumsBypass(bypass: Boolean) {
        _drumsBypass = bypass
        pluginProvider.drumPlugin.setBypass(bypass)
        if (bypass) {
            wiringGraph.drumChainGainL.inputB.set(0.0)
            wiringGraph.drumChainGainR.inputB.set(0.0)
            wiringGraph.drumDirectGainL.inputB.set(1.0)
            wiringGraph.drumDirectGainR.inputB.set(1.0)
        } else {
            wiringGraph.drumChainGainL.inputB.set(1.0)
            wiringGraph.drumChainGainR.inputB.set(1.0)
            wiringGraph.drumDirectGainL.inputB.set(0.0)
            wiringGraph.drumDirectGainR.inputB.set(0.0)
        }
    }
    
    override fun getDrumsBypass(): Boolean = _drumsBypass
    
    // Looper delegations
    override fun setLooperRecord(recording: Boolean) = pluginProvider.looperPlugin.setRecording(recording)
    override fun setLooperPlay(playing: Boolean) = pluginProvider.looperPlugin.setPlaying(playing)
    override fun setLooperOverdub(overdub: Boolean) {}
    override fun clearLooper() = pluginProvider.looperPlugin.clear()
    override fun getLooperPosition(): Float = pluginProvider.looperPlugin.getPosition()
    override fun getLooperDuration(): Double = pluginProvider.looperPlugin.getLoopDuration()

    // HyperLFO delegations (DuoLFO)
    override fun setHyperLfoFreq(index: Int, frequency: Float) {
        val symbol = if (index == 0) DuoLfoSymbol.FREQ_A.symbol else DuoLfoSymbol.FREQ_B.symbol
        setPluginPort(DUO_LFO_URI, symbol, PortValue.FloatValue(frequency))
    }
    override fun setHyperLfoMode(mode: Int) = 
        setPluginPort(DUO_LFO_URI, DuoLfoSymbol.MODE.symbol, PortValue.IntValue(mode)).let {}
    override fun setHyperLfoLink(active: Boolean) = 
        setPluginPort(DUO_LFO_URI, DuoLfoSymbol.LINK.symbol, PortValue.BoolValue(active)).let {}
    override fun getHyperLfoFreq(index: Int): Float {
        val symbol = if (index == 0) DuoLfoSymbol.FREQ_A.symbol else DuoLfoSymbol.FREQ_B.symbol
        return getPluginPort(DUO_LFO_URI, symbol)?.asFloat() ?: 0f
    }
    override fun getHyperLfoMode(): Int = 
        getPluginPort(DUO_LFO_URI, DuoLfoSymbol.MODE.symbol)?.asInt() ?: 0
    override fun getHyperLfoLink(): Boolean = 
        getPluginPort(DUO_LFO_URI, DuoLfoSymbol.LINK.symbol)?.asBoolean() ?: false

    // Distortion delegations
    override fun setDrive(amount: Float) {
        setPluginPort(DISTORTION_URI, DistortionSymbol.DRIVE.symbol, PortValue.FloatValue(amount))
        // Also update direct drum limiters
        val driveVal = 1.0 + (amount * 14.0)
        wiringGraph.drumDirectLimiterL.drive.set(driveVal)
        wiringGraph.drumDirectLimiterR.drive.set(driveVal)
        _driveFlow.value = amount
    }
    override fun setDistortionMix(amount: Float) {
        setPluginPort(DISTORTION_URI, DistortionSymbol.MIX.symbol, PortValue.FloatValue(amount))
        _distortionMixFlow.value = amount
    }
    override fun getDrive(): Float = 
        getPluginPort(DISTORTION_URI, DistortionSymbol.DRIVE.symbol)?.asFloat() ?: 0f
    override fun getDistortionMix(): Float = 
        getPluginPort(DISTORTION_URI, DistortionSymbol.MIX.symbol)?.asFloat() ?: 0.5f

    // Stereo delegations
    override fun setMasterVolume(amount: Float) = 
        setPluginPort(STEREO_URI, StereoSymbol.MASTER_VOL.symbol, PortValue.FloatValue(amount)).let {}
    override fun getMasterVolume(): Float = 
        getPluginPort(STEREO_URI, StereoSymbol.MASTER_VOL.symbol)?.asFloat() ?: 0.7f
        
    override fun setVoicePan(index: Int, pan: Float) {
        if (index in 0 until 12) {
             val symbol = StereoSymbol.entries[index + 2].symbol
             setPluginPort(STEREO_URI, symbol, PortValue.FloatValue(pan))
        }
    }
    override fun getVoicePan(index: Int): Float {
        if (index !in 0 until 12) return 0f
        val symbol = StereoSymbol.entries[index + 2].symbol
        return getPluginPort(STEREO_URI, symbol)?.asFloat() ?: 0f
    }
    override fun setMasterPan(pan: Float) = 
        setPluginPort(STEREO_URI, StereoSymbol.MASTER_PAN.symbol, PortValue.FloatValue(pan)).let {}
    override fun getMasterPan(): Float = 
        getPluginPort(STEREO_URI, StereoSymbol.MASTER_PAN.symbol)?.asFloat() ?: 0f
    override fun setStereoMode(mode: StereoMode) {
        _stereoMode = mode
        setPluginPort(DELAY_URI, DelaySymbol.STEREO_MODE.symbol, PortValue.BoolValue(mode == StereoMode.STEREO_DELAYS))
    }
    override fun getStereoMode(): StereoMode = _stereoMode

    // Vibrato delegation
    override fun setVibrato(amount: Float) {
        setPluginPort(VIBRATO_URI, VibratoSymbol.DEPTH.symbol, PortValue.FloatValue(amount))
        pluginProvider.voicePlugin.setVibrato(amount)
    }
    override fun getVibrato(): Float = 
        getPluginPort(VIBRATO_URI, VibratoSymbol.DEPTH.symbol)?.asFloat() ?: 0f
    
    // Bender delegation
    override fun setBend(amount: Float) {
        setPluginPort(BENDER_URI, BenderSymbol.BEND.symbol, PortValue.FloatValue(amount))
        _bendFlow.value = amount
    }
    override fun getBend(): Float = 
        getPluginPort(BENDER_URI, BenderSymbol.BEND.symbol)?.asFloat() ?: 0f
    
    // Per-String Bender delegation
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        if (pluginProvider.perStringBenderPlugin.setStringBend(stringIndex, bendAmount, voiceMix)) {
            val voiceA = stringIndex * 2
            val voiceB = stringIndex * 2 + 1
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
    
    override fun setSlideBar(yPosition: Float, xPosition: Float) = 
        pluginProvider.perStringBenderPlugin.setSlideBar(yPosition, xPosition)
    override fun releaseSlideBar() = pluginProvider.perStringBenderPlugin.releaseSlideBar()
    override fun resetStringBenders() = pluginProvider.perStringBenderPlugin.resetAll()

    // Voice Delegation
    override fun setVoiceTune(index: Int, tune: Float) = voiceManager.setVoiceTune(index, tune)
    override fun setVoiceGate(index: Int, active: Boolean) = voiceManager.setVoiceGate(index, active)
    override fun setVoiceFeedback(index: Int, amount: Float) { /* Not implemented yet */ }
    override fun setVoiceFmDepth(index: Int, amount: Float) = voiceManager.setVoiceFmDepth(index, amount)
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) = voiceManager.setVoiceEnvelopeSpeed(index, speed)
    override fun setPairSharpness(pairIndex: Int, sharpness: Float) = voiceManager.setPairSharpness(pairIndex, sharpness)
    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        voiceManager.setQuadPitch(quadIndex, pitch)
        _quadPitchFlow.value = voiceManager.getQuadPitchArrayCopy()
    }
    override fun setQuadHold(quadIndex: Int, amount: Float) {
        voiceManager.setQuadHold(quadIndex, amount)
        _quadHoldFlow.value = voiceManager.getQuadHoldArrayCopy()
    }
    override fun setQuadVolume(quadIndex: Int, volume: Float) = voiceManager.setQuadVolume(quadIndex, volume)
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = voiceManager.fadeQuadVolume(quadIndex, targetVolume, durationSeconds)
    override fun setVoiceHold(index: Int, amount: Float) = voiceManager.setVoiceHold(index, amount)
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) = voiceManager.setVoiceWobble(index, wobbleOffset, range)
    override fun setDuoModSource(duoIndex: Int, source: ModSource) = voiceManager.setDuoModSource(duoIndex, source)
    override fun setFmStructure(crossQuad: Boolean) = voiceManager.setFmStructure(crossQuad)
    
    override fun setTotalFeedback(amount: Float) {
        voiceManager.setTotalFeedback(amount)
        wiringGraph.totalFbGain.inputB.set(amount * 20.0)
    }
    override fun setVoiceCoupling(amount: Float) = voiceManager.setVoiceCoupling(amount)

    // Trigger delegations
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {
        pluginProvider.drumPlugin.trigger(type, accent, frequency, tone, decay, p4, p5)
    }
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {
        pluginProvider.drumPlugin.setParameters(type, frequency, tone, decay, p4, p5)
    }
    override fun triggerDrum(type: Int, accent: Float) {
        pluginProvider.drumPlugin.trigger(type, accent)
    }

    // Test tone
    private var testOsc: SineOscillator? = null
    private var testGain: Multiply? = null
    override fun playTestTone(frequency: Float) {
        log.debug { "Playing test tone at ${frequency}Hz" }
        if (!audioEngine.isRunning) audioEngine.start()
        
        if (testOsc == null) {
            testOsc = dspFactory.createSineOscillator()
            testGain = dspFactory.createMultiply()
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
    override fun stopTestTone() { testGain?.inputB?.set(0.0) }

    // Plugin Port Access
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean = 
        pluginProvider.getPlugin(pluginUri)?.setPortValue(symbol, value) ?: false
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? = 
        pluginProvider.getPlugin(pluginUri)?.getPortValue(symbol)

    // Automation Delegation
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) = 
        automationManager.setParameterAutomation(controlId, times, values, count, duration, mode)
    override fun clearParameterAutomation(controlId: String) = 
        automationManager.clearParameterAutomation(controlId)

    // State Getters (Delegated)
    override fun getPeak(): Float = pluginProvider.stereoPlugin.getPeak()
    override fun getCpuLoad(): Float = audioEngine.getCpuLoad()
    
    override fun getVoiceTune(index: Int) = voiceManager.getVoiceTune(index)
    override fun getVoiceFmDepth(index: Int) = voiceManager.getVoiceFmDepth(index)
    override fun getVoiceEnvelopeSpeed(index: Int) = voiceManager.getVoiceEnvelopeSpeed(index)
    override fun getPairSharpness(pairIndex: Int) = voiceManager.getPairSharpness(pairIndex)
    override fun getDuoModSource(duoIndex: Int) = voiceManager.getDuoModSource(duoIndex)
    override fun getQuadPitch(quadIndex: Int) = voiceManager.getQuadPitch(quadIndex)
    override fun getQuadHold(quadIndex: Int) = voiceManager.getQuadHold(quadIndex)
    override fun getQuadVolume(quadIndex: Int) = voiceManager.getQuadVolume(quadIndex)
    override fun getFmStructureCrossQuad() = voiceManager.getFmStructureCrossQuad()
    override fun getTotalFeedback() = voiceManager.getTotalFeedback()
    override fun getVoiceCoupling() = voiceManager.getVoiceCoupling()

    // Rings Resonator
    override fun setResonatorMode(mode: Int) {
        setPluginPort(RESONATOR_URI, ResonatorSymbol.MODE.symbol, PortValue.IntValue(mode))
        wiringGraph.drumDirectResonator.setMode(mode)
    }
    
    private var _resoTargetMix = 0.5f
    private var _resoMix = 0.0f
    
    override fun setResonatorTarget(target: Int) {
        val mix = when (target.coerceIn(0, 2)) {
            0 -> 0.0f
            1 -> 0.5f
            2 -> 1.0f
            else -> 0.5f
        }
        setResonatorTargetMix(mix)
    }
    
    override fun setResonatorTargetMix(targetMix: Float) {
        _resoTargetMix = targetMix
        setPluginPort(RESONATOR_URI, ResonatorSymbol.TARGET_MIX.symbol, PortValue.FloatValue(targetMix))
        updateDirectResonatorGains()
    }
    
    override fun setResonatorStructure(value: Float) {
        setPluginPort(RESONATOR_URI, ResonatorSymbol.STRUCTURE.symbol, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setStructure(value)
    }
    
    override fun setResonatorBrightness(value: Float) {
        setPluginPort(RESONATOR_URI, ResonatorSymbol.BRIGHTNESS.symbol, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setBrightness(value)
    }
    
    override fun setResonatorDamping(value: Float) {
        setPluginPort(RESONATOR_URI, ResonatorSymbol.DAMPING.symbol, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setDamping(value)
    }
    
    override fun setResonatorPosition(value: Float) {
        setPluginPort(RESONATOR_URI, ResonatorSymbol.POSITION.symbol, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setPosition(value)
    }
    
    override fun setResonatorMix(value: Float) {
        _resoMix = value
        setPluginPort(RESONATOR_URI, ResonatorSymbol.MIX.symbol, PortValue.FloatValue(value))
        updateDirectResonatorGains()
    }
    
    private fun updateDirectResonatorGains() {
        val drumExcite = if (_resoTargetMix <= 0.5f) 1.0f else (1.0f - (_resoTargetMix - 0.5f) * 2.0f).coerceIn(0.0f, 1.0f)
        val mixWet = _resoMix.coerceIn(0.0f, 1.0f)
        val mixDry = 1.0f - mixWet
        
        val finalWet = (mixWet * drumExcite).toDouble()
        val finalDry = ((mixDry * drumExcite) + (1.0f - drumExcite)).toDouble()
        
        wiringGraph.drumDirectResoWetGainL.inputB.set(finalWet)
        wiringGraph.drumDirectResoWetGainR.inputB.set(finalWet)
        wiringGraph.drumDirectResoDryGainL.inputB.set(finalDry)
        wiringGraph.drumDirectResoDryGainR.inputB.set(finalDry)
    }
    
    override fun strumResonator(frequency: Float) {
        pluginProvider.resonatorPlugin.strum(frequency)
        wiringGraph.drumDirectResonator.strum(frequency)
    }
    
    override fun getResonatorMode(): Int = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.MODE.symbol)?.asInt() ?: 0
    override fun getResonatorTarget(): Int {
        val mix = getResonatorTargetMix()
        return when {
            mix <= 0.3f -> 0
            mix >= 0.7f -> 2
            else -> 1
        }
    }
    override fun getResonatorTargetMix(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.TARGET_MIX.symbol)?.asFloat() ?: 0f
    override fun getResonatorStructure(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.STRUCTURE.symbol)?.asFloat() ?: 0.25f
    override fun getResonatorBrightness(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.BRIGHTNESS.symbol)?.asFloat() ?: 0.5f
    override fun getResonatorDamping(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.DAMPING.symbol)?.asFloat() ?: 0.3f
    override fun getResonatorPosition(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.POSITION.symbol)?.asFloat() ?: 0.5f
    override fun getResonatorMix(): Float = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.MIX.symbol)?.asFloat() ?: 0f
    override fun getResonatorSnapBack(): Boolean = 
        getPluginPort(RESONATOR_URI, ResonatorSymbol.SNAP_BACK.symbol)?.asBoolean() ?: false
    override fun setResonatorSnapBack(enabled: Boolean) = 
        setPluginPort(RESONATOR_URI, ResonatorSymbol.SNAP_BACK.symbol, PortValue.BoolValue(enabled)).let {}

    // Drums getters
    override fun getDrumFrequency(type: Int): Float = pluginProvider.drumPlugin.getFrequency(type)
    override fun getDrumTone(type: Int): Float = pluginProvider.drumPlugin.getTone(type)
    override fun getDrumDecay(type: Int): Float = pluginProvider.drumPlugin.getDecay(type)
    override fun getDrumP4(type: Int): Float = pluginProvider.drumPlugin.getP4(type)
    override fun getDrumP5(type: Int): Float = pluginProvider.drumPlugin.getP5(type)
    
    // Drum Sources


    override fun setDrumTriggerSource(drumIndex: Int, sourceIndex: Int) {
        if (drumIndex !in 0..2) return
        drumTriggerSources[drumIndex] = sourceIndex
        
        val drumIn = when(drumIndex) {
            0 -> pluginProvider.drumPlugin.inputs["triggerBD"]
            1 -> pluginProvider.drumPlugin.inputs["triggerSD"]
            2 -> pluginProvider.drumPlugin.inputs["triggerHH"]
            else -> null
        } ?: return
        
        drumIn.disconnectAll()
        when (sourceIndex) {
            1 -> pluginProvider.fluxPlugin.outputs["outputT1"]?.connect(drumIn)
            2 -> pluginProvider.fluxPlugin.outputs["outputT2"]?.connect(drumIn)
            3 -> pluginProvider.fluxPlugin.outputs["outputT3"]?.connect(drumIn)
            else -> {}
        }
    }
    
    override fun setDrumPitchSource(drumIndex: Int, sourceIndex: Int) {
        if (drumIndex !in 0..2) return
        drumPitchSources[drumIndex] = sourceIndex
        
        val drumPitchIn = when(drumIndex) {
            0 -> pluginProvider.drumPlugin.inputs["pitchBD"]
            1 -> pluginProvider.drumPlugin.inputs["pitchSD"]
            2 -> pluginProvider.drumPlugin.inputs["pitchHH"]
            else -> null
        } ?: return
        
        drumPitchIn.disconnectAll()
        when (sourceIndex) {
            1 -> pluginProvider.fluxPlugin.outputs["outputX1"]?.connect(drumPitchIn)
            2 -> pluginProvider.fluxPlugin.outputs["output"]?.connect(drumPitchIn) 
            3 -> pluginProvider.fluxPlugin.outputs["outputX3"]?.connect(drumPitchIn)
            else -> {}
        }
    }
    
    override fun getDrumTriggerSource(drumIndex: Int): Int = drumTriggerSources.getOrElse(drumIndex) { 0 }
    override fun getDrumPitchSource(drumIndex: Int): Int = drumPitchSources.getOrElse(drumIndex) { 0 }
    
    // Quad delegations
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) = voiceManager.setQuadPitchSource(quadIndex, sourceIndex)
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = voiceManager.setQuadTriggerSource(quadIndex, sourceIndex)
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = voiceManager.setQuadEnvelopeTriggerMode(quadIndex, enabled)
    override fun getQuadPitchSource(quadIndex: Int) = voiceManager.getQuadPitchSource(quadIndex)
    override fun getQuadTriggerSource(quadIndex: Int) = voiceManager.getQuadTriggerSource(quadIndex)
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int) = voiceManager.getQuadEnvelopeTriggerMode(quadIndex)

    override fun setFluxClockSource(sourceIndex: Int) {
        fluxClockSource = sourceIndex
        val fluxIn = pluginProvider.fluxPlugin.inputs["clock"] ?: return
        fluxIn.disconnectAll()
        when (sourceIndex) {
            1 -> pluginProvider.hyperLfo.output.connect(fluxIn)
            else -> globalTempo.getClockOutput().connect(fluxIn)
        }
    }

    // Beats (Just storage)
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
    override fun setBeatsDensity(index: Int, density: Float) { if (index in 0..2) _beatsDensities[index] = density }
    override fun getBeatsDensity(index: Int): Float = _beatsDensities.getOrElse(index) { 0.5f }
    override fun setBeatsBpm(bpm: Float) { _beatsBpm = bpm }
    override fun getBeatsBpm(): Float = _beatsBpm
    override fun setBeatsOutputMode(mode: Int) { _beatsMode = mode }
    override fun getBeatsOutputMode(): Int = _beatsMode
    override fun setBeatsEuclideanLength(index: Int, length: Int) { if (index in 0..2) _beatsEuclideanLengths[index] = length }
    override fun getBeatsEuclideanLength(index: Int): Int = _beatsEuclideanLengths.getOrElse(index) { 16 }
    override fun setBeatsRandomness(randomness: Float) { _beatsRandomness = randomness }
    override fun getBeatsRandomness(): Float = _beatsRandomness
    override fun setBeatsSwing(swing: Float) { _beatsSwing = swing }
    override fun getBeatsSwing(): Float = _beatsSwing

    // Grains (Generic)
    override fun setGrainsPosition(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.POSITION.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsSize(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.SIZE.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsPitch(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.PITCH.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsDensity(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.DENSITY.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsTexture(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.TEXTURE.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsDryWet(value: Float) = setPluginPort(GRAINS_URI, GrainsSymbol.DRY_WET.symbol, PortValue.FloatValue(value)).let {}
    override fun setGrainsFreeze(frozen: Boolean) = setPluginPort(GRAINS_URI, GrainsSymbol.FREEZE.symbol, PortValue.BoolValue(frozen)).let {}
    override fun setGrainsTrigger(trigger: Boolean) { pluginProvider.grainsPlugin.inputs["trigger"]?.set(if (trigger) 1.0 else 0.0) }
    override fun setGrainsMode(mode: Int) = setPluginPort(GRAINS_URI, GrainsSymbol.MODE.symbol, PortValue.IntValue(mode)).let {}
    override fun getGrainsPosition() = getPluginPort(GRAINS_URI, GrainsSymbol.POSITION.symbol)?.asFloat() ?: 0f
    override fun getGrainsSize() = getPluginPort(GRAINS_URI, GrainsSymbol.SIZE.symbol)?.asFloat() ?: 0f
    override fun getGrainsPitch() = getPluginPort(GRAINS_URI, GrainsSymbol.PITCH.symbol)?.asFloat() ?: 0f
    override fun getGrainsDensity() = getPluginPort(GRAINS_URI, GrainsSymbol.DENSITY.symbol)?.asFloat() ?: 0f
    override fun getGrainsTexture() = getPluginPort(GRAINS_URI, GrainsSymbol.TEXTURE.symbol)?.asFloat() ?: 0f
    override fun getGrainsDryWet() = getPluginPort(GRAINS_URI, GrainsSymbol.DRY_WET.symbol)?.asFloat() ?: 0f
    override fun getGrainsFreeze() = getPluginPort(GRAINS_URI, GrainsSymbol.FREEZE.symbol)?.asBoolean() ?: false
    override fun getGrainsMode() = getPluginPort(GRAINS_URI, GrainsSymbol.MODE.symbol)?.asInt() ?: 0

    // Warps
    private var _warpsCarrierSource = 0
    private var _warpsModulatorSource = 1
    override fun setWarpsAlgorithm(value: Float) = setPluginPort(WARPS_URI, WarpsSymbol.ALGORITHM.symbol, PortValue.FloatValue(value)).let {}
    override fun setWarpsTimbre(value: Float) = setPluginPort(WARPS_URI, WarpsSymbol.TIMBRE.symbol, PortValue.FloatValue(value)).let {}
    override fun setWarpsLevel1(value: Float) = setPluginPort(WARPS_URI, WarpsSymbol.LEVEL1.symbol, PortValue.FloatValue(value)).let {}
    override fun setWarpsLevel2(value: Float) = setPluginPort(WARPS_URI, WarpsSymbol.LEVEL2.symbol, PortValue.FloatValue(value)).let {}
    override fun setWarpsMix(value: Float) = setPluginPort(WARPS_URI, WarpsSymbol.MIX.symbol, PortValue.FloatValue(value)).let {}

    override fun setWarpsCarrierSource(source: Int) {
        _warpsCarrierSource = source
        pluginProvider.warpsPlugin.disconnectCarrier()
        pluginProvider.warpsPlugin.setCarrierSource(source)
        getWarpsSourceOutput(source)?.first?.connect(pluginProvider.warpsPlugin.carrierRouteInput)
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }

    override fun setWarpsModulatorSource(source: Int) {
        _warpsModulatorSource = source
        pluginProvider.warpsPlugin.disconnectModulator()
        pluginProvider.warpsPlugin.setModulatorSource(source)
        getWarpsSourceOutput(source)?.second?.connect(pluginProvider.warpsPlugin.modulatorRouteInput)
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }
    
    override fun getWarpsAlgorithm() = getPluginPort(WARPS_URI, WarpsSymbol.ALGORITHM.symbol)?.asFloat() ?: 0f
    override fun getWarpsTimbre() = getPluginPort(WARPS_URI, WarpsSymbol.TIMBRE.symbol)?.asFloat() ?: 0.5f
    override fun getWarpsLevel1() = getPluginPort(WARPS_URI, WarpsSymbol.LEVEL1.symbol)?.asFloat() ?: 0.5f
    override fun getWarpsLevel2() = getPluginPort(WARPS_URI, WarpsSymbol.LEVEL2.symbol)?.asFloat() ?: 0.5f
    override fun getWarpsCarrierSource() = _warpsCarrierSource
    override fun getWarpsModulatorSource() = _warpsModulatorSource
    override fun getWarpsMix() = getPluginPort(WARPS_URI, WarpsSymbol.MIX.symbol)?.asFloat() ?: 0.5f

    private fun getWarpsSourceOutput(source: Int): Pair<AudioOutput, AudioOutput>? {
        return when (source) {
            0 -> Pair(wiringGraph.voiceSumLeft.output, wiringGraph.voiceSumRight.output)
            1 -> pluginProvider.drumPlugin.outputs["outputLeft"]?.let { l -> pluginProvider.drumPlugin.outputs["outputRight"]?.let { r -> Pair(l, r) } }
            2 -> Pair(wiringGraph.replSumLeft.output, wiringGraph.replSumRight.output)
            3 -> Pair(pluginProvider.hyperLfo.outputA, pluginProvider.hyperLfo.outputB)
            4 -> pluginProvider.resonatorPlugin.outputs["outputLeft"]?.let { l -> pluginProvider.resonatorPlugin.outputs["outputRight"]?.let { r -> Pair(l, r) } }
            5 -> pluginProvider.warpsPlugin.outputs["output"]?.let { l -> pluginProvider.warpsPlugin.outputs["outputRight"]?.let { r -> Pair(l, r) } }
            6 -> pluginProvider.fluxPlugin.outputs["outputX1"]?.let { l -> pluginProvider.fluxPlugin.outputs["outputX3"]?.let { r -> Pair(l, r) } }
            else -> null
        }
    }

    // Flux
    override fun setFluxSpread(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.SPREAD.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxBias(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.BIAS.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxSteps(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.STEPS.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxDejaVu(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.DEJAVU.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxLength(value: Int) = setPluginPort(FLUX_URI, FluxSymbol.LENGTH.symbol, PortValue.IntValue(value)).let {}
    override fun setFluxScale(index: Int) = setPluginPort(FLUX_URI, FluxSymbol.SCALE.symbol, PortValue.IntValue(index)).let {}
    override fun setFluxRate(rate: Float) = setPluginPort(FLUX_URI, FluxSymbol.RATE.symbol, PortValue.FloatValue(rate)).let {}
    override fun setFluxJitter(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.JITTER.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxProbability(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.PROBABILITY.symbol, PortValue.FloatValue(value)).let {}
    override fun setFluxGateLength(value: Float) = setPluginPort(FLUX_URI, FluxSymbol.GATE_LENGTH.symbol, PortValue.FloatValue(value)).let {}
    
    override fun getFluxSpread() = getPluginPort(FLUX_URI, FluxSymbol.SPREAD.symbol)?.asFloat() ?: 0.5f
    override fun getFluxBias() = getPluginPort(FLUX_URI, FluxSymbol.BIAS.symbol)?.asFloat() ?: 0.5f
    override fun getFluxSteps() = getPluginPort(FLUX_URI, FluxSymbol.STEPS.symbol)?.asFloat() ?: 0.5f
    override fun getFluxDejaVu() = getPluginPort(FLUX_URI, FluxSymbol.DEJAVU.symbol)?.asFloat() ?: 0f
    override fun getFluxLength() = getPluginPort(FLUX_URI, FluxSymbol.LENGTH.symbol)?.asInt() ?: 8
    override fun getFluxScale() = getPluginPort(FLUX_URI, FluxSymbol.SCALE.symbol)?.asInt() ?: 0
    override fun getFluxRate() = getPluginPort(FLUX_URI, FluxSymbol.RATE.symbol)?.asFloat() ?: 0.5f
    override fun getFluxJitter() = getPluginPort(FLUX_URI, FluxSymbol.JITTER.symbol)?.asFloat() ?: 0f
    override fun getFluxProbability() = getPluginPort(FLUX_URI, FluxSymbol.PROBABILITY.symbol)?.asFloat() ?: 0.5f
    override fun getFluxGateLength() = getPluginPort(FLUX_URI, FluxSymbol.GATE_LENGTH.symbol)?.asFloat() ?: 0.5f
    override fun getFluxClockSource() = fluxClockSource
}
