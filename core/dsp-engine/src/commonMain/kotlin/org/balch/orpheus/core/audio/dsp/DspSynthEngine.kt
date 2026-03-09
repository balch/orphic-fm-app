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
import org.balch.orpheus.core.plugin.symbols.STEREO_URI
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VibratoSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.tempo.GlobalTempo
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

    /** Native bridge for C++ DSP engine (Android). Null on JVM/WASM. */
    private val nativeBridge = (audioEngine as? NativeDspBridge).also {
        log.info { "NativeDspBridge: ${if (it != null) "AVAILABLE" else "null"} (audioEngine=${audioEngine::class.simpleName})" }
    }

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
    private val _lfoAOutputFlow = MutableStateFlow(0f)
    override val lfoAOutputFlow: StateFlow<Float> = _lfoAOutputFlow.asStateFlow()
    private val _lfoBOutputFlow = MutableStateFlow(0f)
    override val lfoBOutputFlow: StateFlow<Float> = _lfoBOutputFlow.asStateFlow()

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
                    StereoSymbol.MASTER_VOL.controlId -> { setMasterVolume(value.asFloat()); true }
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
        syncNativeBridgeState()

        // Initial defaults
        setDelayMix(0f)
        setDrumsBypass(true)
        setDrumTriggerSource(0, 0) // Kick -> Internal (manual only)
        setDrumTriggerSource(1, 0) // Snare -> Internal (manual only)
        setDrumTriggerSource(2, 0) // HiHat -> Internal (manual only)
    }

    override fun syncToNative() = syncNativeBridgeState()

    /** Push current voice state to C++ engine so it matches Kotlin on startup. */
    private fun syncNativeBridgeState() {
        val bridge = nativeBridge ?: return
        val vp = pluginProvider.voicePlugin
        for (duo in 0..5) {
            val engineOrdinal = vp.getDuoEngine(duo)
            val voiceA = duo * 2
            val cppIndex = plaitsEngineOrdinalToCpp(engineOrdinal)
            // Always keep voices active in C++ — it's the only audio path on Android
            bridge.nativeSetVoiceActive(voiceA, true)
            bridge.nativeSetVoiceActive(voiceA + 1, true)
            bridge.nativeSetVoiceEngine(voiceA, cppIndex)
            bridge.nativeSetVoiceEngine(voiceA + 1, cppIndex)
            bridge.nativeSetVoiceHarmonics(voiceA, vp.getDuoHarmonics(duo))
            bridge.nativeSetVoiceHarmonics(voiceA + 1, vp.getDuoHarmonics(duo))
            bridge.nativeSetVoiceTimbre(voiceA, vp.getDuoSharpness(duo))
            bridge.nativeSetVoiceTimbre(voiceA + 1, vp.getDuoSharpness(duo))
            // Speech engine: morph is per-voice envSpeed (word selection); otherwise duo morph
            if (engineOrdinal == SPEECH_ENGINE_ORDINAL) {
                bridge.nativeSetVoiceMorph(voiceA, vp.getEnvSpeed(voiceA))
                bridge.nativeSetVoiceMorph(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
            } else {
                bridge.nativeSetVoiceMorph(voiceA, vp.getDuoMorph(duo))
                bridge.nativeSetVoiceMorph(voiceA + 1, vp.getDuoMorph(duo))
            }
            bridge.nativeSetVoiceDecay(voiceA, vp.getEnvSpeed(voiceA))
            bridge.nativeSetVoiceDecay(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
            // Sync tune with correct MIDI note conversion
            bridge.nativeSetVoiceTune(voiceA, tuneToMidiNote(voiceA, voiceManager.getVoiceTune(voiceA)))
            bridge.nativeSetVoiceTune(voiceA + 1, tuneToMidiNote(voiceA + 1, voiceManager.getVoiceTune(voiceA + 1)))
            // Sync mod source and depth
            val modSrc = voiceManager.getDuoModSource(duo)
            val modLvl = voiceManager.getDuoModSourceLevel(duo)
            bridge.nativeSetPort("org.balch.orpheus.plugins.voice", "duo_mod_source_$duo", modSrc.ordinal.toFloat())
            bridge.nativeSetPort("org.balch.orpheus.plugins.voice", "duo_mod_source_level_$duo", modLvl)
            log.debug { "syncNative: duo=$duo engine=$engineOrdinal→cpp=$cppIndex active=true modSrc=$modSrc modLvl=$modLvl" }
        }
        // Sync per-quad volume
        for (quad in 0..2) {
            bridge.nativeSetPort("org.balch.orpheus.plugins.stereo", "quad_vol_$quad", voiceManager.getQuadVolume(quad))
        }
        // Sync per-voice pan (default pans match graph build-time values)
        val defaultPans = floatArrayOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f, 0f, 0f, 0f, 0f)
        for (v in 0 until 12) {
            val pan = pluginProvider.getPlugin(STEREO_URI)
                ?.getPortValue("voice_pan_$v")?.asFloat() ?: defaultPans[v]
            forwardPanToNative(v, pan)
        }
        // Sync LFO parameters
        val lfoUri = DuoLfoSymbol.MODE.uri
        bridge.nativeSetPort(lfoUri, DuoLfoSymbol.FREQ_A.symbol, getHyperLfoFreq(0))
        bridge.nativeSetPort(lfoUri, DuoLfoSymbol.FREQ_B.symbol, getHyperLfoFreq(1))
        bridge.nativeSetPort(lfoUri, DuoLfoSymbol.MODE.symbol, getHyperLfoMode().toFloat())
        bridge.nativeSetPort(lfoUri, DuoLfoSymbol.SHAPE.symbol, getPort(DuoLfoSymbol.SHAPE)?.asFloat() ?: 1f)
        // Sync bender parameters
        bridge.nativeSetPort(BENDER_URI, BenderSymbol.MAX_BEND.symbol, getPort(BenderSymbol.MAX_BEND)?.asFloat() ?: 24f)
        bridge.nativeSetPort(BENDER_URI, BenderSymbol.RANDOM_DEPTH.symbol, getPort(BenderSymbol.RANDOM_DEPTH)?.asFloat() ?: 0.1f)
        bridge.nativeSetPort(BENDER_URI, BenderSymbol.TIMBRE_MOD.symbol, getPort(BenderSymbol.TIMBRE_MOD)?.asFloat() ?: 0.3f)
        bridge.nativeSetPort(BENDER_URI, BenderSymbol.SPRING_VOL.symbol, getPort(BenderSymbol.SPRING_VOL)?.asFloat() ?: 0.4f)
        bridge.nativeSetPort(BENDER_URI, BenderSymbol.TENSION_VOL.symbol, getPort(BenderSymbol.TENSION_VOL)?.asFloat() ?: 0.015f)
    }

    private fun setupListeners() {
        // Register Voice Plugin Listener
        pluginProvider.voicePlugin.setListener(object : VoicePlugin.Listener {
            override fun onVoiceParamChange(index: Int, param: String, value: Any) {
                when (param) {
                    "tune" -> setVoiceTune(index, value as Float)
                    "mod_depth" -> voiceManager.setVoiceFmDepth(index, value as Float)
                    "env_speed" -> {
                        voiceManager.setVoiceEnvelopeSpeed(index, value as Float)
                        // Forward as LPG decay to C++ engine
                        nativeBridge?.nativeSetVoiceDecay(index, value)
                        // Speech engine: envSpeed overrides morph for word selection
                        val duoIndex = index / 2
                        if (pluginProvider.voicePlugin.getDuoEngine(duoIndex) == SPEECH_ENGINE_ORDINAL) {
                            nativeBridge?.nativeSetVoiceMorph(index, value)
                        }
                    }
                    "duo_sharpness" -> {
                        voiceManager.setDuoSharpness(index, value as Float)
                        // Forward timbre to C++ for both voices in the duo
                        val voiceA = index * 2
                        nativeBridge?.nativeSetVoiceTimbre(voiceA, value)
                        nativeBridge?.nativeSetVoiceTimbre(voiceA + 1, value)
                    }
                    "duo_mod_source" -> voiceManager.setDuoModSource(index, ModSource.entries[value as Int])
                    "duo_engine" -> {
                        val engineOrdinal = value as Int
                        voiceManager.setDuoEngine(index, engineOrdinal)
                        // Forward engine index to C++ for both voices in the duo
                        // Always keep active — C++ is the only audio path on Android
                        val voiceA = index * 2
                        val cppIndex = plaitsEngineOrdinalToCpp(engineOrdinal)
                        nativeBridge?.nativeSetVoiceActive(voiceA, true)
                        nativeBridge?.nativeSetVoiceActive(voiceA + 1, true)
                        nativeBridge?.nativeSetVoiceEngine(voiceA, cppIndex)
                        nativeBridge?.nativeSetVoiceEngine(voiceA + 1, cppIndex)
                        // Speech engine: override C++ morph with per-voice envSpeed for word selection
                        if (engineOrdinal == SPEECH_ENGINE_ORDINAL) {
                            val vp = pluginProvider.voicePlugin
                            nativeBridge?.nativeSetVoiceMorph(voiceA, vp.getEnvSpeed(voiceA))
                            nativeBridge?.nativeSetVoiceMorph(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
                        }
                    }
                    "duo_harmonics" -> {
                        voiceManager.setDuoHarmonics(index, value as Float)
                        val voiceA = index * 2
                        nativeBridge?.nativeSetVoiceHarmonics(voiceA, value)
                        nativeBridge?.nativeSetVoiceHarmonics(voiceA + 1, value)
                    }
                    "duo_prosody" -> voiceManager.setDuoProsody(index, value as Float)
                    "duo_speed" -> voiceManager.setDuoSpeed(index, value as Float)
                    "duo_morph" -> {
                        voiceManager.setDuoMorph(index, value as Float)
                        // Speech engine: morph is controlled by envSpeed, not duo_morph
                        if (pluginProvider.voicePlugin.getDuoEngine(index) != SPEECH_ENGINE_ORDINAL) {
                            val voiceA = index * 2
                            nativeBridge?.nativeSetVoiceMorph(voiceA, value)
                            nativeBridge?.nativeSetVoiceMorph(voiceA + 1, value)
                        }
                    }
                    "duo_mod_source_level" -> voiceManager.setDuoModSourceLevel(index, value as Float)
                    "quad_pitch" -> setQuadPitch(index, value as Float)
                    "quad_hold" -> {
                        voiceManager.setQuadHold(index, value as Float)
                        // Forward raw hold to C++ for all voices in the quad
                        val startVoice = index * 4
                        val holdAmount = value as Float
                        for (i in startVoice until (startVoice + 4).coerceAtMost(12)) {
                            nativeBridge?.nativeSetVoiceHold(i, holdAmount)
                        }
                    }
                    "quad_volume" -> setQuadVolume(index, value as Float)
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
    private var startRequested = false

    override fun start() {
        if (startRequested || audioEngine.isRunning) return
        startRequested = true
        log.debug { "Starting Shared Audio Engine..." }
        audioEngine.start()
        syncNativeBridgeState() // Re-sync after C++ engine is created

        // Load the default wiring graph into the C++ engine
        nativeBridge?.nativeLoadGraph(buildDefaultWiringGraph())?.also { result ->
            log.info { "nativeLoadGraph result: $result" }
        }

        monitoringJob = if (nativeBridge != null) {
            // Native C++ engine: poll monitor data from C++ via JNI
            monitoringScope.launch(dispatcherProvider.io) {
                val monitorBuf = FloatArray(20) // OrpheusMonitorData: peak_l, peak_r, cpu, voice_levels[12], lfo, master, bend, lfo_a, lfo_b
                while (isActive) {
                    nativeBridge.nativeGetMonitor(monitorBuf)
                    _peakFlow.value = maxOf(monitorBuf[0], monitorBuf[1])
                    _cpuLoadFlow.value = monitorBuf[2] * 100f
                    // voice_levels[0..11] at indices 3..14
                    val levels = FloatArray(12) { monitorBuf[3 + it] }
                    _voiceLevelsFlow.value = levels
                    _lfoOutputFlow.value = monitorBuf[15]
                    _masterLevelFlow.value = monitorBuf[16]
                    _bendFlow.value = monitorBuf[17]
                    _lfoAOutputFlow.value = monitorBuf[18]
                    _lfoBOutputFlow.value = monitorBuf[19]
                    delay(MONITOR_POLL_INTERVAL_MS)
                }
            }
        } else {
            // Kotlin DSP graph: poll from plugin units directly
            monitoringScope.launch(dispatcherProvider.io) {
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
                    _lfoAOutputFlow.value = pluginProvider.hyperLfo.getCurrentValueA()
                    _lfoBOutputFlow.value = pluginProvider.hyperLfo.getCurrentValueB()

                    val computedMaster = (voiceSum / 12f).coerceIn(0f, 1f)
                    _masterLevelFlow.value = maxOf(currentPeak.coerceIn(0f, 1f), computedMaster)

                    delay(MONITOR_POLL_INTERVAL_MS)
                }
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
        nativeBridge?.nativeSetDelayMix(amount)
        setPort(DelaySymbol.MIX, PortValue.FloatValue(amount))
        setPort(DistortionSymbol.DRY_LEVEL, PortValue.FloatValue(1.0f - amount))
    }

    override fun setDelayModDepth(index: Int, amount: Float) {
        val ps = if (index == 0) DelaySymbol.MOD_DEPTH_1 else DelaySymbol.MOD_DEPTH_2
        setPort(ps, PortValue.FloatValue(amount))  // forwards to C++ via nativeSetPort
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
        val chainGain = if (bypass) 0.0f else 1.0f
        val directGain = if (bypass) 1.0f else 0.0f
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
        // Forward to C++ ODWG graph
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_chain_gain_l", chainGain)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_chain_gain_r", chainGain)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_gain_l", directGain)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_gain_r", directGain)
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
        nativeBridge?.nativeSetDrive(amount)
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
    override fun setMasterVolume(amount: Float) {
        nativeBridge?.nativeSetMasterVolume(amount)
        setPort(StereoSymbol.MASTER_VOL, PortValue.FloatValue(amount))
    }
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
        nativeBridge?.nativeSetVibrato(amount)
        setPort(VibratoSymbol.DEPTH, PortValue.FloatValue(amount))
        pluginProvider.voicePlugin.setVibrato(amount)
    }
    override fun getVibrato(): Float =
        getPort(VibratoSymbol.DEPTH)?.asFloat() ?: 0f

    // Bender delegation
    override fun setBend(amount: Float) {
        nativeBridge?.nativeSetBend(amount)
        setPort(BenderSymbol.BEND, PortValue.FloatValue(amount))
        _bendFlow.value = amount
    }
    override fun getBend(): Float =
        getPort(BenderSymbol.BEND)?.asFloat() ?: 0f

    // Per-String Bender delegation
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        val shouldTriggerVoice = pluginProvider.perStringBenderPlugin.setStringBend(stringIndex, bendAmount, voiceMix)
        // Always forward bend to C++ engine (JSyn return value only indicates voice trigger, not bend success)
        nativeBridge?.let { bridge ->
            bridge.nativeSetPort(BENDER_URI, "string_bend_$stringIndex", bendAmount)
            bridge.nativeSetPort(BENDER_URI, "string_mix_$stringIndex", voiceMix)
            bridge.nativeSetPort(BENDER_URI, "string_active_$stringIndex", 1f)
        }
        if (shouldTriggerVoice) {
            val voiceA = stringIndex * 2
            val voiceB = stringIndex * 2 + 1
            if (voiceA < 12) setVoiceGate(voiceA, true)
            if (voiceB < 12) setVoiceGate(voiceB, true)
        }
    }

    override fun releaseStringBend(stringIndex: Int): Int {
        val (springDuration, shouldRelease) = pluginProvider.perStringBenderPlugin.releaseString(stringIndex)
        nativeBridge?.let { bridge ->
            bridge.nativeSetPort(BENDER_URI, "string_bend_$stringIndex", 0f)
            bridge.nativeSetPort(BENDER_URI, "string_active_$stringIndex", 0f)
        }
        if (shouldRelease) {
             val voiceA = stringIndex * 2
             val voiceB = stringIndex * 2 + 1
             if (voiceA < 12) setVoiceGate(voiceA, false)
             if (voiceB < 12) setVoiceGate(voiceB, false)
        }
        return springDuration
    }

    override fun setSlideBar(yPosition: Float, xPosition: Float) {
        pluginProvider.perStringBenderPlugin.setSlideBar(yPosition, xPosition)
        nativeBridge?.let { bridge ->
            bridge.nativeSetPort(BENDER_URI, "slide_bar_y", yPosition)
            bridge.nativeSetPort(BENDER_URI, "slide_bar_x", xPosition)
        }
    }
    override fun releaseSlideBar() {
        pluginProvider.perStringBenderPlugin.releaseSlideBar()
        nativeBridge?.let { bridge ->
            bridge.nativeSetPort(BENDER_URI, "slide_bar_y", 0f)
            bridge.nativeSetPort(BENDER_URI, "slide_bar_x", 0f)
        }
    }
    override fun resetStringBenders() = pluginProvider.perStringBenderPlugin.resetAll()

    // Voice Delegation

    /**
     * Convert JSyn tune (0..1) to MIDI note for C++ engine.
     * JSyn: freq = 55 * 2^(tune*4) * pitchMult * 2^((quadPitch-0.5)*2)
     * MIDI: freq = 440 * 2^((note-69)/12)
     * Solving: note = 33 + 48*tune + pitchMultSemitones + 24*(quadPitch-0.5)
     */
    private fun tuneToMidiNote(index: Int, tune: Float): Float {
        val quadIndex = index / 4
        val quadPitch = voiceManager.getQuadPitch(quadIndex)
        return 33f + tune * 48f + VOICE_PITCH_MULT_SEMITONES[index] + 24f * (quadPitch - 0.5f)
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        nativeBridge?.nativeSetVoiceTune(index, tuneToMidiNote(index, tune))
        voiceManager.setVoiceTune(index, tune)
    }
    override fun setVoiceGate(index: Int, active: Boolean) {
        // Send actual gate state — C++ handles hold-based gate override internally
        nativeBridge?.nativeSetVoiceGate(index, active)
        voiceManager.setVoiceGate(index, active)
    }
    override fun setVoiceFeedback(index: Int, amount: Float) { /* Not implemented yet */ }
    override fun setVoiceFmDepth(index: Int, amount: Float) = voiceManager.setVoiceFmDepth(index, amount)
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) = voiceManager.setVoiceEnvelopeSpeed(index, speed)
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) = voiceManager.setDuoSharpness(duoIndex, sharpness)
    override fun setQuadPitch(quadIndex: Int, pitch: Float) {
        voiceManager.setQuadPitch(quadIndex, pitch)
        // Re-sync MIDI notes for all voices in this quad since pitch offset changed
        val startVoice = quadIndex * 4
        for (i in startVoice until (startVoice + 4).coerceAtMost(12)) {
            nativeBridge?.nativeSetVoiceTune(i, tuneToMidiNote(i, voiceManager.getVoiceTune(i)))
        }
    }
    override fun setQuadHold(quadIndex: Int, amount: Float) {
        voiceManager.setQuadHold(quadIndex, amount)
        // Forward raw hold level to C++ — it computes scaled hold using envSpeed internally
        val startVoice = quadIndex * 4
        for (i in startVoice until (startVoice + 4).coerceAtMost(12)) {
            nativeBridge?.nativeSetVoiceHold(i, amount)
        }
    }
    override fun setQuadVolume(quadIndex: Int, volume: Float) {
        voiceManager.setQuadVolume(quadIndex, volume)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.stereo", "quad_vol_$quadIndex", volume)
    }
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = voiceManager.fadeQuadVolume(quadIndex, targetVolume, durationSeconds)
    override fun setVoiceHold(index: Int, amount: Float) {
        voiceManager.setVoiceHold(index, amount)
        // Forward raw hold level to C++
        nativeBridge?.nativeSetVoiceHold(index, amount)
    }
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
        nativeBridge?.nativeTriggerDrum(type, accent)
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
        // Intercept voice pan to compute constant-power L/R gains for C++ graph
        if (pluginUri == STEREO_URI && symbol.startsWith("voice_pan_")) {
            val pan = value.asFloat()  // -1..+1
            val voiceIndex = symbol.removePrefix("voice_pan_").toIntOrNull()
            if (voiceIndex != null) {
                forwardPanToNative(voiceIndex, pan)
            }
        } else {
            nativeBridge?.nativeSetPort(pluginUri, symbol, value.asFloat())
        }
        val result = pluginProvider.getPlugin(pluginUri)?.setPortValue(symbol, value) ?: false
        // Keep bendFlow in sync when Bender BEND is set externally (gesture, MIDI, AI)
        if (result && pluginUri == BENDER_URI && symbol == BenderSymbol.BEND.symbol) {
            _bendFlow.value = value.asFloat()
        }
        return result
    }

    /** Compute constant-power pan gains and forward to C++ graph. */
    private fun forwardPanToNative(voiceIndex: Int, pan: Float) {
        val angle = ((pan + 1f) * 0.5f) * (kotlin.math.PI.toFloat() * 0.5f)
        val leftGain = kotlin.math.cos(angle)
        val rightGain = kotlin.math.sin(angle)
        nativeBridge?.nativeSetPort(STEREO_URI, "voice_pan_L_$voiceIndex", leftGain)
        nativeBridge?.nativeSetPort(STEREO_URI, "voice_pan_R_$voiceIndex", rightGain)
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

        // Forward to C++ engine
        val wetF = finalWet.toFloat()
        val dryF = finalDry.toFloat()
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_l", wetF)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_r", wetF)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_l", dryF)
        nativeBridge?.nativeSetPort("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_r", dryF)
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

    /**
     * Maps Kotlin PlaitsEngineId ordinal+1 (1-based engineOrdinal) to
     * C++ Plaits engine registration index. 0 = engine off (no Plaits rendering).
     *
     * C++ registration order (from plaits/dsp/voice.cc Init()):
     * 0-7: engine2 (VA VCF, Phase Dist, 6-Op×3, Wave Terrain, String Machine, Chiptune)
     * 8: VirtualAnalog, 9: Waveshaping, 10: FM, 11: Grain, 12: Additive,
     * 13: Wavetable, 14: Chord, 15: Speech, 16: Swarm, 17: Noise,
     * 18: Particle, 19: String, 20: Modal, 21: BassDrum, 22: SnareDrum, 23: HiHat
     */
    private fun plaitsEngineOrdinalToCpp(engineOrdinal: Int): Int {
        if (engineOrdinal <= 0) return -1 // Engine 0 (OSC mode): triangle+square with ADSR+hold
        val idx = engineOrdinal - 1
        return if (idx < CPP_ENGINE_MAP.size) CPP_ENGINE_MAP[idx] else 0
    }

    companion object {
        private const val MONITOR_POLL_INTERVAL_MS = 200L
        /** VoicePlugin engineOrdinal for SPEECH (PlaitsEngineId.SPEECH.ordinal + 1). */
        private const val SPEECH_ENGINE_ORDINAL = 17

        // Per-voice pitch multiplier in semitones (matches DspVoiceManager voice list):
        // 0,1=bass(0.5x→-12), 2-5=mid(1.0x→0), 6,7=high(2.0x→+12), 8-11=repl(1.0x→0)
        private val VOICE_PITCH_MULT_SEMITONES = floatArrayOf(
            -12f, -12f, 0f, 0f, 0f, 0f, 12f, 12f, 0f, 0f, 0f, 0f
        )

        // PlaitsEngineId ordinals (0-based) → C++ engine indices
        private val CPP_ENGINE_MAP = intArrayOf(
            21, // ANALOG_BASS_DRUM
            22, // ANALOG_SNARE_DRUM
            23, // METALLIC_HI_HAT
            21, // FM_DRUM (mapped to bass drum — custom engine not in MI source)
            10, // FM
            17, // NOISE
            9,  // WAVESHAPING
            8,  // VIRTUAL_ANALOG
            12, // ADDITIVE
            11, // GRAIN
            19, // STRING
            20, // MODAL
            18, // PARTICLE
            16, // SWARM
            14, // CHORD
            13, // WAVETABLE
            15, // SPEECH
        )
    }
}
