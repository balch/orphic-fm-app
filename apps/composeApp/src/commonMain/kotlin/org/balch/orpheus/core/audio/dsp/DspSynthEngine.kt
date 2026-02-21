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
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BENDER_URI
import org.balch.orpheus.core.plugin.symbols.BenderSymbol
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VibratoSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.debug.DebugViewModel.Companion.POLL_INTERVAL_MS
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin

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
    private val automationManager: DspAutomationManager,
    private val synthController: SynthController
) : SynthEngine {

    private val log = logging("DspSynthEngine")

    private fun setPort(ps: PortSymbol, value: PortValue): Boolean =
        setPluginPort(ps.uri, ps.symbol, value)
    private fun getPort(ps: PortSymbol): PortValue? =
        getPluginPort(ps.uri, ps.symbol)

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

    private val _bendFlow = MutableStateFlow(0f)
    override val bendFlow: StateFlow<Float> = _bendFlow.asStateFlow()

    // Monitoring
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        voiceManager.initialize()
        wiringGraph.initialize(voiceManager)

        // Wire SynthController delegates for plugin port routing.
        // Route through facade methods for ports with side effects,
        // fall back to direct setPluginPort for simple cases.
        synthController.setDelegates(
            setter = { id, value ->
                when (id) {
                    DistortionSymbol.DRIVE.controlId -> { setDrive(value.asFloat()); true }
                    DelaySymbol.MIX.controlId -> { setDelayMix(value.asFloat()); true }
                    VibratoSymbol.DEPTH.controlId -> { setVibrato(value.asFloat()); true }
                    ResonatorSymbol.MODE.controlId -> { setResonatorMode(value.asInt()); true }
                    ResonatorSymbol.STRUCTURE.controlId -> { setResonatorStructure(value.asFloat()); true }
                    ResonatorSymbol.BRIGHTNESS.controlId -> { setResonatorBrightness(value.asFloat()); true }
                    ResonatorSymbol.DAMPING.controlId -> { setResonatorDamping(value.asFloat()); true }
                    ResonatorSymbol.POSITION.controlId -> { setResonatorPosition(value.asFloat()); true }
                    ResonatorSymbol.MIX.controlId -> { setResonatorMix(value.asFloat()); true }
                    ResonatorSymbol.TARGET_MIX.controlId -> { setResonatorTargetMix(value.asFloat()); true }
                    DrumSymbol.BYPASS.controlId -> { setDrumsBypass(value.asBoolean()); true }
                    FluxSymbol.CLOCK_SOURCE.controlId -> { setFluxClockSource(value.asInt()); true }
                    WarpsSymbol.CARRIER_SOURCE.controlId -> { setWarpsCarrierSource(value.asInt()); true }
                    WarpsSymbol.MODULATOR_SOURCE.controlId -> { setWarpsModulatorSource(value.asInt()); true }
                    else -> setPluginPort(id.uri, id.symbol, value)
                }
            },
            getter = { id ->
                getPluginPort(id.uri, id.symbol)
            }
        )

        setupListeners()
        setupAutomation()

        // Initial defaults
        setDelayMix(0f)
        setDrumsBypass(true)
        setDrumTriggerSource(0, 0) // Kick -> Internal (manual only)
        setDrumTriggerSource(1, 0) // Snare -> Internal (manual only)
        setDrumTriggerSource(2, 0) // HiHat -> Internal (manual only)
    }

    private fun setupListeners() {
        // Register Voice Plugin Listener
        pluginProvider.voicePlugin.setListener(object : VoicePlugin.Listener {
            override fun onVoiceParamChange(index: Int, param: String, value: Any) {
                when (param) {
                    "tune" -> voiceManager.setVoiceTune(index, value as Float)
                    "mod_depth" -> voiceManager.setVoiceFmDepth(index, value as Float)
                    "env_speed" -> voiceManager.setVoiceEnvelopeSpeed(index, value as Float)
                    "duo_sharpness" -> voiceManager.setDuoSharpness(index, value as Float)
                    "duo_mod_source" -> voiceManager.setDuoModSource(index, ModSource.entries[value as Int])
                    "duo_engine" -> voiceManager.setDuoEngine(index, value as Int)
                    "duo_harmonics" -> voiceManager.setDuoHarmonics(index, value as Float)
                    "duo_prosody" -> voiceManager.setDuoProsody(index, value as Float)
                    "duo_speed" -> voiceManager.setDuoSpeed(index, value as Float)
                    "duo_morph" -> voiceManager.setDuoMorph(index, value as Float)
                    "duo_mod_source_level" -> voiceManager.setDuoModSourceLevel(index, value as Float)
                    "quad_pitch" -> voiceManager.setQuadPitch(index, value as Float)
                    "quad_hold" -> voiceManager.setQuadHold(index, value as Float)
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
        val ps = if (index == 0) DelaySymbol.TIME_1 else DelaySymbol.TIME_2
        setPort(ps, PortValue.FloatValue(time))
    }

    override fun setDelayFeedback(amount: Float) {
        setPort(DelaySymbol.FEEDBACK, PortValue.FloatValue(amount))
    }

    override fun setDelayMix(amount: Float) {
        setPort(DelaySymbol.MIX, PortValue.FloatValue(amount))
        setPort(DistortionSymbol.DRY_LEVEL, PortValue.FloatValue(1.0f - amount))
    }

    override fun setDelayModDepth(index: Int, amount: Float) {
        val ps = if (index == 0) DelaySymbol.MOD_DEPTH_1 else DelaySymbol.MOD_DEPTH_2
        setPort(ps, PortValue.FloatValue(amount))
    }

    override fun getDelayTime(index: Int): Float {
        val ps = if (index == 0) DelaySymbol.TIME_1 else DelaySymbol.TIME_2
        return getPort(ps)?.asFloat() ?: 0.3f
    }
    override fun getDelayFeedback(): Float =
        getPort(DelaySymbol.FEEDBACK)?.asFloat() ?: 0.5f
    override fun getDelayMix(): Float =
        getPort(DelaySymbol.MIX)?.asFloat() ?: 0.5f
    override fun getDelayModDepth(index: Int): Float {
        val ps = if (index == 0) DelaySymbol.MOD_DEPTH_1 else DelaySymbol.MOD_DEPTH_2
        return getPort(ps)?.asFloat() ?: 0f
    }

    // Drums bypass (side effects: rewires drum routing path)
    private fun setDrumsBypass(bypass: Boolean) {
        _drumsBypass = bypass
        pluginProvider.drumPlugin.setBypass(bypass)
        if (bypass) {
            // MAIN mode — drums go direct to stereo output (bypass effects chain)
            wiringGraph.drumChainGainL.inputB.set(0.0)
            wiringGraph.drumChainGainR.inputB.set(0.0)
            wiringGraph.drumDirectGainL.inputB.set(1.0)
            wiringGraph.drumDirectGainR.inputB.set(1.0)
        } else {
            // FX mode — drums route through effects chain (resonator/distortion/delay)
            wiringGraph.drumChainGainL.inputB.set(1.0)
            wiringGraph.drumChainGainR.inputB.set(1.0)
            wiringGraph.drumDirectGainL.inputB.set(0.0)
            wiringGraph.drumDirectGainR.inputB.set(0.0)
        }
    }

    // TTS delegations
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) = pluginProvider.ttsPlugin.loadAudio(samples, sampleRate)
    override fun playTts() = pluginProvider.ttsPlugin.play()
    override fun stopTts() = pluginProvider.ttsPlugin.stopPlayback()
    override fun isTtsPlaying(): Boolean = pluginProvider.ttsPlugin.isPlaying()

    // Looper delegations
    override fun setLooperRecord(recording: Boolean) = pluginProvider.looperPlugin.setRecording(recording)
    override fun setLooperPlay(playing: Boolean) = pluginProvider.looperPlugin.setPlaying(playing)
    override fun setLooperOverdub(overdub: Boolean) {}
    override fun clearLooper() = pluginProvider.looperPlugin.clear()
    override fun getLooperPosition(): Float = pluginProvider.looperPlugin.getPosition()
    override fun getLooperDuration(): Double = pluginProvider.looperPlugin.getLoopDuration()

    // HyperLFO delegations (DuoLFO)
    override fun setHyperLfoFreq(index: Int, frequency: Float) {
        val ps = if (index == 0) DuoLfoSymbol.FREQ_A else DuoLfoSymbol.FREQ_B
        setPort(ps, PortValue.FloatValue(frequency))
    }
    override fun setHyperLfoMode(mode: Int) =
        setPort(DuoLfoSymbol.MODE, PortValue.IntValue(mode)).let {}
    override fun setHyperLfoLink(active: Boolean) =
        setPort(DuoLfoSymbol.LINK, PortValue.BoolValue(active)).let {}
    override fun getHyperLfoFreq(index: Int): Float {
        val ps = if (index == 0) DuoLfoSymbol.FREQ_A else DuoLfoSymbol.FREQ_B
        return getPort(ps)?.asFloat() ?: 0.5f
    }
    override fun getHyperLfoMode(): Int =
        getPort(DuoLfoSymbol.MODE)?.asInt() ?: 1
    override fun getHyperLfoLink(): Boolean =
        getPort(DuoLfoSymbol.LINK)?.asBoolean() ?: false

    // Distortion delegations
    override fun setDrive(amount: Float) {
        setPort(DistortionSymbol.DRIVE, PortValue.FloatValue(amount))
        // Also update direct drum limiters
        val driveVal = 1.0 + (amount * 14.0)
        wiringGraph.drumDirectLimiterL.drive.set(driveVal)
        wiringGraph.drumDirectLimiterR.drive.set(driveVal)
    }
    override fun setDistortionMix(amount: Float) {
        setPort(DistortionSymbol.MIX, PortValue.FloatValue(amount))
    }
    override fun getDrive(): Float =
        getPort(DistortionSymbol.DRIVE)?.asFloat() ?: 0f
    override fun getDistortionMix(): Float =
        getPort(DistortionSymbol.MIX)?.asFloat() ?: 0.5f

    // Stereo delegations
    override fun setMasterVolume(amount: Float) =
        setPort(StereoSymbol.MASTER_VOL, PortValue.FloatValue(amount)).let {}
    override fun getMasterVolume(): Float =
        getPort(StereoSymbol.MASTER_VOL)?.asFloat() ?: 0.7f

    override fun setVoicePan(index: Int, pan: Float) {
        if (index in 0 until 12) {
             val ps = StereoSymbol.entries[index + 2]
             setPort(ps, PortValue.FloatValue(pan))
        }
    }
    override fun getVoicePan(index: Int): Float {
        if (index !in 0 until 12) return 0f
        val ps = StereoSymbol.entries[index + 2]
        return getPort(ps)?.asFloat() ?: 0f
    }
    override fun setMasterPan(pan: Float) =
        setPort(StereoSymbol.MASTER_PAN, PortValue.FloatValue(pan)).let {}
    override fun getMasterPan(): Float =
        getPort(StereoSymbol.MASTER_PAN)?.asFloat() ?: 0f
    override fun setStereoMode(mode: StereoMode) {
        _stereoMode = mode
        setPort(DelaySymbol.STEREO_MODE, PortValue.BoolValue(mode == StereoMode.STEREO_DELAYS))
    }
    override fun getStereoMode(): StereoMode = _stereoMode

    // Vibrato delegation (side effect: also updates voicePlugin)
    override fun setVibrato(amount: Float) {
        setPort(VibratoSymbol.DEPTH, PortValue.FloatValue(amount))
        pluginProvider.voicePlugin.setVibrato(amount)
    }
    override fun getVibrato(): Float =
        getPort(VibratoSymbol.DEPTH)?.asFloat() ?: 0f

    // Bender delegation
    override fun setBend(amount: Float) {
        setPort(BenderSymbol.BEND, PortValue.FloatValue(amount))
        _bendFlow.value = amount
    }
    override fun getBend(): Float =
        getPort(BenderSymbol.BEND)?.asFloat() ?: 0f

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
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) = voiceManager.setDuoSharpness(duoIndex, sharpness)
    override fun setQuadPitch(quadIndex: Int, pitch: Float) = voiceManager.setQuadPitch(quadIndex, pitch)
    override fun setQuadHold(quadIndex: Int, amount: Float) = voiceManager.setQuadHold(quadIndex, amount)
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
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean {
        val result = pluginProvider.getPlugin(pluginUri)?.setPortValue(symbol, value) ?: false
        // Keep bendFlow in sync when Bender BEND is set externally (gesture, MIDI, AI)
        if (result && pluginUri == BENDER_URI && symbol == BenderSymbol.BEND.symbol) {
            _bendFlow.value = value.asFloat()
        }
        return result
    }
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
    override fun getDuoSharpness(duoIndex: Int) = voiceManager.getDuoSharpness(duoIndex)
    override fun getDuoModSource(duoIndex: Int) = voiceManager.getDuoModSource(duoIndex)
    override fun getQuadPitch(quadIndex: Int) = voiceManager.getQuadPitch(quadIndex)
    override fun getQuadHold(quadIndex: Int) = voiceManager.getQuadHold(quadIndex)
    override fun getQuadVolume(quadIndex: Int) = voiceManager.getQuadVolume(quadIndex)
    override fun getFmStructureCrossQuad() = voiceManager.getFmStructureCrossQuad()
    override fun getTotalFeedback() = voiceManager.getTotalFeedback()
    override fun getVoiceCoupling() = voiceManager.getVoiceCoupling()

    // Rings Resonator (side effects: also updates drumDirectResonator)
    private fun setResonatorMode(mode: Int) {
        setPort(ResonatorSymbol.MODE, PortValue.IntValue(mode))
        wiringGraph.drumDirectResonator.setMode(mode)
    }

    private var _resoTargetMix = 0.5f
    private var _resoMix = 0.0f

    private fun setResonatorTargetMix(targetMix: Float) {
        _resoTargetMix = targetMix
        setPort(ResonatorSymbol.TARGET_MIX, PortValue.FloatValue(targetMix))
        updateDirectResonatorGains()
    }

    private fun setResonatorStructure(value: Float) {
        setPort(ResonatorSymbol.STRUCTURE, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setStructure(value)
    }

    private fun setResonatorBrightness(value: Float) {
        setPort(ResonatorSymbol.BRIGHTNESS, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setBrightness(value)
    }

    private fun setResonatorDamping(value: Float) {
        setPort(ResonatorSymbol.DAMPING, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setDamping(value)
    }

    private fun setResonatorPosition(value: Float) {
        setPort(ResonatorSymbol.POSITION, PortValue.FloatValue(value))
        wiringGraph.drumDirectResonator.setPosition(value)
    }

    private fun setResonatorMix(value: Float) {
        _resoMix = value
        setPort(ResonatorSymbol.MIX, PortValue.FloatValue(value))
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

    private fun strumResonator(frequency: Float) {
        pluginProvider.resonatorPlugin.strum(frequency)
        wiringGraph.drumDirectResonator.strum(frequency)
    }

    // Drums getters
    override fun getDrumFrequency(type: Int): Float = pluginProvider.drumPlugin.getFrequency(type)
    override fun getDrumTone(type: Int): Float = pluginProvider.drumPlugin.getTone(type)
    override fun getDrumDecay(type: Int): Float = pluginProvider.drumPlugin.getDecay(type)
    override fun getDrumP4(type: Int): Float = pluginProvider.drumPlugin.getP4(type)
    override fun getDrumP5(type: Int): Float = pluginProvider.drumPlugin.getP5(type)

    // Drum Sources (side effects: rewires audio inputs)
    private fun setDrumTriggerSource(drumIndex: Int, sourceIndex: Int) {
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

    private fun setDrumPitchSource(drumIndex: Int, sourceIndex: Int) {
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

    // Quad delegations
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) = voiceManager.setQuadPitchSource(quadIndex, sourceIndex)
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = voiceManager.setQuadTriggerSource(quadIndex, sourceIndex)
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = voiceManager.setQuadEnvelopeTriggerMode(quadIndex, enabled)
    override fun getQuadPitchSource(quadIndex: Int) = voiceManager.getQuadPitchSource(quadIndex)
    override fun getQuadTriggerSource(quadIndex: Int) = voiceManager.getQuadTriggerSource(quadIndex)
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int) = voiceManager.getQuadEnvelopeTriggerMode(quadIndex)

    // Flux clock source (side effect: rewires clock input)
    private fun setFluxClockSource(sourceIndex: Int) {
        fluxClockSource = sourceIndex
        val fluxIn = pluginProvider.fluxPlugin.inputs["clock"] ?: return
        fluxIn.disconnectAll()
        when (sourceIndex) {
            1 -> pluginProvider.hyperLfo.output.connect(fluxIn)
            else -> globalTempo.getBeatClockOutput().connect(fluxIn)
        }
    }

    // Warps source routing (side effects: rewires audio graph)
    private var _warpsCarrierSource = 0
    private var _warpsModulatorSource = 1

    private fun setWarpsCarrierSource(source: Int) {
        _warpsCarrierSource = source
        pluginProvider.warpsPlugin.disconnectCarrier()
        pluginProvider.warpsPlugin.setCarrierSource(source)
        getWarpsSourceOutput(source)?.first?.connect(pluginProvider.warpsPlugin.carrierRouteInput)
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }

    private fun setWarpsModulatorSource(source: Int) {
        _warpsModulatorSource = source
        pluginProvider.warpsPlugin.disconnectModulator()
        pluginProvider.warpsPlugin.setModulatorSource(source)
        getWarpsSourceOutput(source)?.second?.connect(pluginProvider.warpsPlugin.modulatorRouteInput)
        pluginProvider.warpsPlugin.disconnectDry()
        getWarpsSourceOutput(_warpsCarrierSource)?.first?.connect(pluginProvider.warpsPlugin.dryInputLeft)
        getWarpsSourceOutput(_warpsModulatorSource)?.second?.connect(pluginProvider.warpsPlugin.dryInputRight)
    }

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
}
