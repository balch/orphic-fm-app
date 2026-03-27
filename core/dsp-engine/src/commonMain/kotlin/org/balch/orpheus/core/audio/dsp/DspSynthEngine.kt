package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.ControlPort
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
import org.balch.orpheus.core.triggers.DrumTriggerSource
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin

/**
 * Pure control surface implementation of SynthEngine.
 * All audio processing is handled by the C++ engine via NativeDspBridge.
 * This class forwards control changes and manages plugin state.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspSynthEngine(
    private val audioEngine: AudioEngine,
    private val pluginProvider: DspPluginProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val globalTempo: GlobalTempo,
    private val voiceManager: DspVoiceManager,
    private val synthController: SynthController
) : SynthEngine {

    private val log = logging("DspSynthEngine")

    /** Native bridge for C++ DSP engine — all platforms now use NativeDspBridge. */
    private val nativeBridge = audioEngine as NativeDspBridge

    override val hasNativeEngine: Boolean get() = true

    private fun setPort(ps: PortSymbol, value: PortValue): Boolean =
        setPluginPort(ps.uri, ps.symbol, value)
    private fun getPort(ps: PortSymbol): PortValue? =
        getPluginPort(ps.uri, ps.symbol)

    // State
    private var _stereoMode = StereoMode.VOICE_PAN

    // Composed delegates
    val monitor = SynthEngineMonitor(nativeBridge, dispatcherProvider)
    val routing = SynthEngineRouting(audioEngine, nativeBridge, pluginProvider)

    // Delegate SynthEngine interface StateFlow properties to monitor
    override val peakFlow: StateFlow<Float> get() = monitor.peakFlow
    override val cpuLoadFlow: StateFlow<Float> get() = monitor.cpuLoadFlow
    override val voiceLevelsFlow: StateFlow<FloatArray> get() = monitor.voiceLevelsFlow
    override val lfoOutputFlow: StateFlow<Float> get() = monitor.lfoOutputFlow
    override val lfoAOutputFlow: StateFlow<Float> get() = monitor.lfoAOutputFlow
    override val lfoBOutputFlow: StateFlow<Float> get() = monitor.lfoBOutputFlow
    override val masterLevelFlow: StateFlow<Float> get() = monitor.masterLevelFlow
    override val bendFlow: StateFlow<Float> get() = monitor.bendFlow
    override val lfoVizFlow: StateFlow<FloatArray> get() = monitor.lfoVizFlow
    override val warpsCarrierVizFlow: StateFlow<FloatArray> get() = monitor.warpsCarrierVizFlow
    override val warpsModVizFlow: StateFlow<FloatArray> get() = monitor.warpsModVizFlow
    override val warpsOutVizFlow: StateFlow<FloatArray> get() = monitor.warpsOutVizFlow
    override val delayInVizFlow: StateFlow<FloatArray> get() = monitor.delayInVizFlow
    override val delayFbVizFlow: StateFlow<FloatArray> get() = monitor.delayFbVizFlow
    override val delayOutVizFlow: StateFlow<FloatArray> get() = monitor.delayOutVizFlow
    override val reverbInVizFlow: StateFlow<FloatArray> get() = monitor.reverbInVizFlow
    override val reverbOutVizFlow: StateFlow<FloatArray> get() = monitor.reverbOutVizFlow
    override val fluxCvVizFlow: StateFlow<FloatArray> get() = monitor.fluxCvVizFlow
    override val resoInVizFlow: StateFlow<FloatArray> get() = monitor.resoInVizFlow
    override val resoOutVizFlow: StateFlow<FloatArray> get() = monitor.resoOutVizFlow
    override val drumOutVizFlow: StateFlow<FloatArray> get() = monitor.drumOutVizFlow
    override val grainsInVizFlow: StateFlow<FloatArray> get() = monitor.grainsInVizFlow
    override val grainsOutVizFlow: StateFlow<FloatArray> get() = monitor.grainsOutVizFlow
    override val lfoCh1VizFlow: StateFlow<FloatArray> get() = monitor.lfoCh1VizFlow
    override val lfoCh2VizFlow: StateFlow<FloatArray> get() = monitor.lfoCh2VizFlow
    override val lfoCh3VizFlow: StateFlow<FloatArray> get() = monitor.lfoCh3VizFlow
    override val bassOutVizFlow: StateFlow<FloatArray> get() = monitor.bassOutVizFlow
    override val djVizFlowA: StateFlow<FloatArray> get() = monitor.djVizFlowA
    override val djVizFlowB: StateFlow<FloatArray> get() = monitor.djVizFlowB
    override val djOutVizFlow: StateFlow<FloatArray> get() = monitor.djOutVizFlow
    override val masterOutVizFlow: StateFlow<FloatArray> get() = monitor.masterOutVizFlow
    override val hornInVizFlow: StateFlow<FloatArray> get() = monitor.hornInVizFlow
    override val hornOutVizFlow: StateFlow<FloatArray> get() = monitor.hornOutVizFlow
    override val hornPhaseVizFlow: StateFlow<FloatArray> get() = monitor.hornPhaseVizFlow
    override val wooferPhaseVizFlow: StateFlow<FloatArray> get() = monitor.wooferPhaseVizFlow
    override val tidesCh0VizFlow: StateFlow<FloatArray> get() = monitor.tidesCh0VizFlow
    override val tidesCh1VizFlow: StateFlow<FloatArray> get() = monitor.tidesCh1VizFlow
    override val tidesCh2VizFlow: StateFlow<FloatArray> get() = monitor.tidesCh2VizFlow
    override val tidesCh3VizFlow: StateFlow<FloatArray> get() = monitor.tidesCh3VizFlow

    init {
        voiceManager.initialize()

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
                    VibratoSymbol.RATE.controlId -> { nativeBridge.nativeSetVibratoRate(value.asFloat()); true }
                    ResonatorSymbol.MODE.controlId -> { setResonatorMode(value.asInt()); true }
                    ResonatorSymbol.STRUCTURE.controlId -> { setResonatorStructure(value.asFloat()); true }
                    ResonatorSymbol.BRIGHTNESS.controlId -> { setResonatorBrightness(value.asFloat()); true }
                    ResonatorSymbol.DAMPING.controlId -> { setResonatorDamping(value.asFloat()); true }
                    ResonatorSymbol.POSITION.controlId -> { setResonatorPosition(value.asFloat()); true }
                    ResonatorSymbol.MIX.controlId -> { routing.setResonatorMix(value.asFloat()); true }
                    ResonatorSymbol.TARGET_MIX.controlId -> { routing.setResonatorTargetMix(value.asFloat()); true }
                    DrumSymbol.BYPASS.controlId -> { routing.setDrumsBypass(value.asBoolean()); true }
                    FluxSymbol.CLOCK_SOURCE.controlId -> { routing.setFluxClockSource(value.asInt()); true }
                    WarpsSymbol.CARRIER_SOURCE.controlId -> { routing.setWarpsCarrierSource(value.asInt()); true }
                    WarpsSymbol.MODULATOR_SOURCE.controlId -> { routing.setWarpsModulatorSource(value.asInt()); true }
                    else -> setPluginPort(id.uri, id.symbol, value)
                }
            },
            getter = { id ->
                getPluginPort(id.uri, id.symbol)
            },
            nativeSync = ::syncNativeBridgeState
        )

        setupListeners()
        syncNativeBridgeState()

        // Initial defaults
        setDelayMix(0f)
        routing.setDrumsBypass(true)
        routing.setDrumTriggerSource(0, 0) // Kick -> Internal (manual only)
        routing.setDrumTriggerSource(1, 0) // Snare -> Internal (manual only)
        routing.setDrumTriggerSource(2, 0) // HiHat -> Internal (manual only)
    }

    override fun syncToNative() = syncNativeBridgeState()

    /** Push current voice state to C++ engine so it matches Kotlin on startup. */
    private fun syncNativeBridgeState() {
        // Reset all voice gates to prevent stale gates from previous preset/T-gen routing
        for (v in 0 until 15) {
            nativeBridge.nativeSetVoiceGate(v, false)
        }
        val vp = pluginProvider.voicePlugin
        for (duo in 0..5) {
            val engineOrdinal = vp.getDuoEngine(duo)
            val voiceA = duo * 2
            val cppIndex = plaitsEngineOrdinalToCpp(engineOrdinal)
            // Set engine BEFORE active to avoid race: audio thread may see active=1
            // with stale engine_index=0, triggering the Plaits fallback path.
            nativeBridge.nativeSetVoiceEngine(voiceA, cppIndex)
            nativeBridge.nativeSetVoiceEngine(voiceA + 1, cppIndex)
            // Always keep voices active in C++ — it's the only audio path
            nativeBridge.nativeSetVoiceActive(voiceA, true)
            nativeBridge.nativeSetVoiceActive(voiceA + 1, true)
            nativeBridge.nativeSetVoiceHarmonics(voiceA, vp.getDuoHarmonics(duo))
            nativeBridge.nativeSetVoiceHarmonics(voiceA + 1, vp.getDuoHarmonics(duo))
            nativeBridge.nativeSetVoiceTimbre(voiceA, vp.getDuoSharpness(duo))
            nativeBridge.nativeSetVoiceTimbre(voiceA + 1, vp.getDuoSharpness(duo))
            // Speech engine: morph is per-voice envSpeed (word selection); otherwise duo morph
            if (engineOrdinal == SPEECH_ENGINE_ORDINAL) {
                nativeBridge.nativeSetVoiceMorph(voiceA, vp.getEnvSpeed(voiceA))
                nativeBridge.nativeSetVoiceMorph(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
            } else {
                nativeBridge.nativeSetVoiceMorph(voiceA, vp.getDuoMorph(duo))
                nativeBridge.nativeSetVoiceMorph(voiceA + 1, vp.getDuoMorph(duo))
            }
            nativeBridge.nativeSetVoiceDecay(voiceA, vp.getEnvSpeed(voiceA))
            nativeBridge.nativeSetVoiceDecay(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
            // Sync tune with correct MIDI note conversion
            nativeBridge.nativeSetVoiceTune(voiceA, tuneToMidiNote(voiceA, voiceManager.getVoiceTune(voiceA)))
            nativeBridge.nativeSetVoiceTune(voiceA + 1, tuneToMidiNote(voiceA + 1, voiceManager.getVoiceTune(voiceA + 1)))
            // Sync mod source and depth
            val modSrc = voiceManager.getDuoModSource(duo)
            val modLvl = voiceManager.getDuoModSourceLevel(duo)
            pluginProvider.voicePlugin.setDuoModSource(duo, modSrc.ordinal)
            pluginProvider.voicePlugin.setDuoModSourceLevel(duo, modLvl)
            log.debug { "syncNative: duo=$duo engine=$engineOrdinal→cpp=$cppIndex active=true modSrc=$modSrc modLvl=$modLvl" }
        }
        // Sync per-quad volume
        for (quad in 0..2) {
            pluginProvider.stereoPlugin.setQuadVolume(quad, voiceManager.getQuadVolume(quad))
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
        audioEngine.setPort(lfoUri, DuoLfoSymbol.FREQ_A.symbol, getHyperLfoFreq(0))
        audioEngine.setPort(lfoUri, DuoLfoSymbol.FREQ_B.symbol, getHyperLfoFreq(1))
        audioEngine.setPort(lfoUri, DuoLfoSymbol.MODE.symbol, getHyperLfoMode().toFloat())
        audioEngine.setPort(lfoUri, DuoLfoSymbol.SHAPE.symbol, getPort(DuoLfoSymbol.SHAPE)?.asFloat() ?: 1f)
        // Sync vibrato rate (dedicated sine oscillator, default 5 Hz)
        nativeBridge.nativeSetVibratoRate(getPort(VibratoSymbol.RATE)?.asFloat() ?: 5f)
        nativeBridge.nativeSetVibrato(getVibrato())
        // Sync bender parameters
        audioEngine.setPort(BENDER_URI, BenderSymbol.MAX_BEND.symbol, getPort(BenderSymbol.MAX_BEND)?.asFloat() ?: 24f)
        audioEngine.setPort(BENDER_URI, BenderSymbol.RANDOM_DEPTH.symbol, getPort(BenderSymbol.RANDOM_DEPTH)?.asFloat() ?: 0.1f)
        audioEngine.setPort(BENDER_URI, BenderSymbol.TIMBRE_MOD.symbol, getPort(BenderSymbol.TIMBRE_MOD)?.asFloat() ?: 0.3f)
        audioEngine.setPort(BENDER_URI, BenderSymbol.TENSION_VOL.symbol, getPort(BenderSymbol.TENSION_VOL)?.asFloat() ?: 0.015f)
        // Sync master volume (default 0.7)
        nativeBridge.nativeSetMasterVolume(getMasterVolume())
        // Sync resonator state (mix=0 → bypassed, target_mix=0.5 → both sources)
        val resoUri = ResonatorSymbol.MIX.uri
        val mix = routing.resoMix
        audioEngine.setPort(resoUri, "mix", mix)
        pluginProvider.resonatorPlugin.setMixGains(mix, 1f - mix)
        val tm = routing.resoTargetMix
        val drumEx = if (tm <= 0.5f) 1f else (1f - (tm - 0.5f) * 2f).coerceIn(0f, 1f)
        val synthEx = if (tm >= 0.5f) 1f else (tm * 2f).coerceIn(0f, 1f)
        pluginProvider.resonatorPlugin.setTargetMixGains(drumEx, synthEx)
        // Sync global voice parameters
        pluginProvider.voicePlugin.setCouplingDepth(voiceManager.getVoiceCoupling())
        pluginProvider.voicePlugin.setTotalFeedback(voiceManager.getTotalFeedback())
        pluginProvider.voicePlugin.setFmCrossQuad(voiceManager.getFmStructureCrossQuad())
        // Sync Flux clock source (not a control port, so generic loop won't catch it)
        pluginProvider.fluxPlugin.setClockSource(routing.fluxClockSource)
        // Sync trigger router source selectors
        for (i in 0..2) {
            pluginProvider.fluxPlugin.setDrumTriggerSource(i, routing.drumTriggerSources[i])
            // Map DrumTriggerSource pitch ordinals to X output index (1=X1, 2=X2, 3=X3)
            pluginProvider.fluxPlugin.setDrumPitchSource(i, routing.drumPitchSources[i] - DrumTriggerSource.FLUX_X1.ordinal + 1)
            // Activate drum voice in C++ if it has an external trigger source
            if (routing.drumTriggerSources[i] != 0) {
                nativeBridge.nativeSetVoiceActive(12 + i, true) // kDrumVoiceStart=12
            }
            pluginProvider.fluxPlugin.setQuadTriggerSource(i, voiceManager.getQuadTriggerSource(i))
            pluginProvider.fluxPlugin.setQuadPitchSource(i, voiceManager.getQuadPitchSource(i))
            pluginProvider.fluxPlugin.setQuadTriggerMode(i, voiceManager.getQuadEnvelopeTriggerMode(i))
        }

        // Generic sync: forward ALL plugin control port values to C++ native bridge.
        // This catches reverb, distortion, delay, warps, clouds, drums, grains, flux,
        // and any other plugins not explicitly synced above.
        for (plugin in pluginProvider.plugins) {
            val uri = plugin.info.uri
            for (port in plugin.ports) {
                if (port !is ControlPort || !port.isInput) continue
                val value = plugin.getPortValue(port.symbol) ?: continue
                if (uri == STEREO_URI && port.symbol.startsWith("voice_pan_")) {
                    // Pan ports need constant-power L/R conversion (already synced above,
                    // but harmless to re-sync via the correct path)
                    val voiceIndex = port.symbol.removePrefix("voice_pan_").toIntOrNull()
                    if (voiceIndex != null) forwardPanToNative(voiceIndex, value.asFloat())
                } else {
                    audioEngine.setPort(uri, port.symbol, value.asFloat())
                }
            }
        }
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
                        nativeBridge.nativeSetVoiceDecay(index, value)
                        // Speech engine: envSpeed overrides morph for word selection
                        val duoIndex = index / 2
                        if (pluginProvider.voicePlugin.getDuoEngine(duoIndex) == SPEECH_ENGINE_ORDINAL) {
                            nativeBridge.nativeSetVoiceMorph(index, value)
                        }
                    }
                    "duo_sharpness" -> {
                        voiceManager.setDuoSharpness(index, value as Float)
                        // Forward timbre to C++ for both voices in the duo
                        val voiceA = index * 2
                        nativeBridge.nativeSetVoiceTimbre(voiceA, value)
                        nativeBridge.nativeSetVoiceTimbre(voiceA + 1, value)
                    }
                    "duo_mod_source" -> {
                        val modSourceOrdinal = value as Int
                        voiceManager.setDuoModSource(index, ModSource.entries[modSourceOrdinal])
                        pluginProvider.voicePlugin.setDuoModSource(index, modSourceOrdinal)
                    }
                    "duo_engine" -> {
                        val engineOrdinal = value as Int
                        voiceManager.setDuoEngine(index, engineOrdinal)
                        // Forward engine index to C++ for both voices in the duo
                        // Always keep active — C++ is the only audio path on Android
                        val voiceA = index * 2
                        val cppIndex = plaitsEngineOrdinalToCpp(engineOrdinal)
                        nativeBridge.nativeSetVoiceEngine(voiceA, cppIndex)
                        nativeBridge.nativeSetVoiceEngine(voiceA + 1, cppIndex)
                        nativeBridge.nativeSetVoiceActive(voiceA, true)
                        nativeBridge.nativeSetVoiceActive(voiceA + 1, true)
                        // Speech engine: override C++ morph with per-voice envSpeed for word selection
                        if (engineOrdinal == SPEECH_ENGINE_ORDINAL) {
                            val vp = pluginProvider.voicePlugin
                            nativeBridge.nativeSetVoiceMorph(voiceA, vp.getEnvSpeed(voiceA))
                            nativeBridge.nativeSetVoiceMorph(voiceA + 1, vp.getEnvSpeed(voiceA + 1))
                        }
                    }
                    "duo_harmonics" -> {
                        voiceManager.setDuoHarmonics(index, value as Float)
                        val voiceA = index * 2
                        nativeBridge.nativeSetVoiceHarmonics(voiceA, value)
                        nativeBridge.nativeSetVoiceHarmonics(voiceA + 1, value)
                    }
                    "duo_prosody" -> voiceManager.setDuoProsody(index, value as Float)
                    "duo_speed" -> voiceManager.setDuoSpeed(index, value as Float)
                    "duo_morph" -> {
                        voiceManager.setDuoMorph(index, value as Float)
                        // Speech engine: morph is controlled by envSpeed, not duo_morph
                        if (pluginProvider.voicePlugin.getDuoEngine(index) != SPEECH_ENGINE_ORDINAL) {
                            val voiceA = index * 2
                            nativeBridge.nativeSetVoiceMorph(voiceA, value)
                            nativeBridge.nativeSetVoiceMorph(voiceA + 1, value)
                        }
                    }
                    "duo_mod_source_level" -> {
                        val level = value as Float
                        voiceManager.setDuoModSourceLevel(index, level)
                        pluginProvider.voicePlugin.setDuoModSourceLevel(index, level)
                    }
                    "quad_pitch" -> setQuadPitch(index, value as Float)
                    "quad_hold" -> {
                        val holdAmount = value as Float
                        voiceManager.setQuadHold(index, holdAmount)
                        // Forward raw hold to C++ for all voices in the quad
                        val startVoice = index * 4
                        for (i in startVoice until (startVoice + 4).coerceAtMost(12)) {
                            nativeBridge.nativeSetVoiceHold(i, holdAmount)
                        }
                    }
                    "quad_volume" -> setQuadVolume(index, value as Float)
                    "quad_trigger_source" -> setQuadTriggerSource(index, value as Int)
                    "quad_pitch_source" -> setQuadPitchSource(index, value as Int)
                    "quad_env_trigger_mode" -> {
                        val enabled = value as Boolean
                        voiceManager.setQuadEnvelopeTriggerMode(index, enabled)
                        pluginProvider.fluxPlugin.setQuadTriggerMode(index, enabled)
                    }
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
                if (type == "trigger") routing.setDrumTriggerSource(drumIndex, value)
                if (type == "pitch") routing.setDrumPitchSource(drumIndex, value)
            }
            override fun onBypassChange(bypass: Boolean) {
                routing.setDrumsBypass(bypass)
            }
        })
    }

    // ═══════════════════════════════════════════════════════════
    // SynthEngine Implementation
    // ═══════════════════════════════════════════════════════════

    override fun start() {
        if (monitor.startRequested || audioEngine.isRunning) return
        monitor.startRequested = true
        log.debug { "Starting Shared Audio Engine..." }
        audioEngine.start()
        syncNativeBridgeState() // Re-sync after C++ engine is created

        // Load the default wiring graph into the C++ engine
        nativeBridge.nativeLoadGraph(buildDefaultWiringGraph()).also { result ->
            log.info { "nativeLoadGraph result: $result" }
        }

        // Re-sync port map values after graph load (graph load resets node defaults)
        syncNativeBridgeState()

        // Poll monitor data from C++ via native bridge
        monitor.startMonitoring()
        // Start viz polling if it was requested before the engine was running
        if (monitor.vizRequested) {
            monitor.setVizEnabled(true, true)
        }
        log.debug { "Audio Engine Started" }
    }

    override fun setVizEnabled(enabled: Boolean) {
        monitor.setVizEnabled(enabled, audioEngine.isRunning)
    }

    override fun setTurntableVizEnabled(enabled: Boolean) {
        if (enabled) monitor.startTurntableViz() else monitor.stopTurntableViz()
    }

    override fun getCurrentTime(): Double = audioEngine.getCurrentTime()

    override fun stop() {
        log.debug { "Stopping Audio Engine..." }
        monitor.stopMonitoring()
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
        nativeBridge.nativeSetDelayMix(amount)
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

    // TTS delegations
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) = pluginProvider.ttsPlugin.loadAudio(samples, sampleRate)
    override fun playTts() = pluginProvider.ttsPlugin.play()
    override fun stopTts() = pluginProvider.ttsPlugin.stopPlayback()
    override fun isTtsPlaying(): Boolean = pluginProvider.ttsPlugin.isPlaying()

    // Looper delegations
    override fun setLooperRecord(recording: Boolean) = pluginProvider.looperPlugin.setRecording(recording)
    override fun setLooperPlay(playing: Boolean) = pluginProvider.looperPlugin.setPlaying(playing)
    override fun setLooperOverdub(overdub: Boolean) {}
    override fun setLooperQuantize(enabled: Boolean) =
        pluginProvider.looperPlugin.setQuantize(enabled)
    override fun setLooperLevel(level: Float) =
        pluginProvider.looperPlugin.setLevel(level)
    override fun clearLooper() = pluginProvider.looperPlugin.clear()
    override fun getLooperPosition(): Float =
        audioEngine.getPort(LOOPER_URI, "position")
    override fun getLooperDuration(): Double =
        audioEngine.getPort(LOOPER_URI, "duration").toDouble()

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
        nativeBridge.nativeSetDrive(amount)
        setPort(DistortionSymbol.DRIVE, PortValue.FloatValue(amount))
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
        nativeBridge.nativeSetMasterVolume(amount)
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
        nativeBridge.nativeSetVibrato(amount)
        setPort(VibratoSymbol.DEPTH, PortValue.FloatValue(amount))
        pluginProvider.voicePlugin.setVibrato(amount)
    }
    override fun getVibrato(): Float =
        getPort(VibratoSymbol.DEPTH)?.asFloat() ?: 0f

    // Bender delegation
    override fun setBend(amount: Float) {
        nativeBridge.nativeSetBend(amount)
        setPort(BenderSymbol.BEND, PortValue.FloatValue(amount))
        monitor.updateBend(amount)
    }
    override fun getBend(): Float =
        getPort(BenderSymbol.BEND)?.asFloat() ?: 0f

    // Per-String Bender delegation
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        val shouldTriggerVoice = pluginProvider.perStringBenderPlugin.setStringBend(stringIndex, bendAmount, voiceMix)
        // Always forward bend to C++ engine — plugin return value only indicates voice trigger, not bend success
        audioEngine.setPort(BENDER_URI, "string_bend_$stringIndex", bendAmount)
        audioEngine.setPort(BENDER_URI, "string_mix_$stringIndex", voiceMix)
        audioEngine.setPort(BENDER_URI, "string_active_$stringIndex", 1f)
        if (shouldTriggerVoice) {
            val voiceA = stringIndex * 2
            val voiceB = stringIndex * 2 + 1
            if (voiceA < 12) setVoiceGate(voiceA, true)
            if (voiceB < 12) setVoiceGate(voiceB, true)
        }
    }

    override fun releaseStringBend(stringIndex: Int): Int {
        val (springDuration, shouldRelease) = pluginProvider.perStringBenderPlugin.releaseString(stringIndex)
        audioEngine.setPort(BENDER_URI, "string_bend_$stringIndex", 0f)
        audioEngine.setPort(BENDER_URI, "string_active_$stringIndex", 0f)
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
        audioEngine.setPort(BENDER_URI, "slide_bar_y", yPosition)
        audioEngine.setPort(BENDER_URI, "slide_bar_x", xPosition)
    }
    override fun releaseSlideBar() {
        pluginProvider.perStringBenderPlugin.releaseSlideBar()
        audioEngine.setPort(BENDER_URI, "slide_bar_y", 0f)
        audioEngine.setPort(BENDER_URI, "slide_bar_x", 0f)
    }
    override fun resetStringBenders() = pluginProvider.perStringBenderPlugin.resetAll()

    // Voice Delegation

    /**
     * Convert tune (0..1) to MIDI note for C++ engine.
     * Tune: freq = 55 * 2^(tune*4) * pitchMult * 2^((quadPitch-0.5)*2)
     * MIDI: freq = 440 * 2^((note-69)/12)
     * Solving: note = 33 + 48*tune + pitchMultSemitones + 24*(quadPitch-0.5)
     */
    private fun tuneToMidiNote(index: Int, tune: Float): Float {
        val quadIndex = index / 4
        val quadPitch = voiceManager.getQuadPitch(quadIndex)
        return 33f + tune * 48f + VOICE_PITCH_MULT_SEMITONES[index] + 24f * (quadPitch - 0.5f)
    }

    override fun setVoiceTune(index: Int, tune: Float) {
        nativeBridge.nativeSetVoiceTune(index, tuneToMidiNote(index, tune))
        voiceManager.setVoiceTune(index, tune)
    }
    override fun setVoiceGate(index: Int, active: Boolean) {
        // Send actual gate state — C++ handles hold-based gate override internally
        nativeBridge.nativeSetVoiceGate(index, active)
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
            nativeBridge.nativeSetVoiceTune(i, tuneToMidiNote(i, voiceManager.getVoiceTune(i)))
        }
    }
    override fun setQuadHold(quadIndex: Int, amount: Float) {
        voiceManager.setQuadHold(quadIndex, amount)
        // Forward raw hold level to C++ — it computes scaled hold using envSpeed internally
        val startVoice = quadIndex * 4
        for (i in startVoice until (startVoice + 4).coerceAtMost(12)) {
            nativeBridge.nativeSetVoiceHold(i, amount)
        }
    }
    override fun setQuadVolume(quadIndex: Int, volume: Float) {
        voiceManager.setQuadVolume(quadIndex, volume)
        pluginProvider.stereoPlugin.setQuadVolume(quadIndex, volume)
    }
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) = voiceManager.fadeQuadVolume(quadIndex, targetVolume, durationSeconds)
    override fun setVoiceHold(index: Int, amount: Float) {
        voiceManager.setVoiceHold(index, amount)
        // Forward raw hold level to C++
        nativeBridge.nativeSetVoiceHold(index, amount)
    }
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) = voiceManager.setVoiceWobble(index, wobbleOffset, range)
    override fun setDuoModSource(duoIndex: Int, source: ModSource) = voiceManager.setDuoModSource(duoIndex, source)
    override fun setFmStructure(crossQuad: Boolean) {
        voiceManager.setFmStructure(crossQuad)
        pluginProvider.voicePlugin.setFmCrossQuad(crossQuad)
    }

    override fun setTotalFeedback(amount: Float) {
        voiceManager.setTotalFeedback(amount)
        pluginProvider.voicePlugin.setTotalFeedback(amount)
    }
    override fun setVoiceCoupling(amount: Float) {
        voiceManager.setVoiceCoupling(amount)
        pluginProvider.voicePlugin.setCouplingDepth(amount)
    }

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

    // Test tone (no-op — removed with Kotlin DSP graph)
    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}

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
            audioEngine.setPort(pluginUri, symbol, value.asFloat())
        }
        val result = pluginProvider.getPlugin(pluginUri)?.setPortValue(symbol, value) ?: false
        // Keep bendFlow in sync when Bender BEND is set externally (gesture, MIDI, AI)
        if (result && pluginUri == BENDER_URI && symbol == BenderSymbol.BEND.symbol) {
            monitor.updateBend(value.asFloat())
        }
        return result
    }

    /** Compute constant-power pan gains and forward to C++ graph. */
    private fun forwardPanToNative(voiceIndex: Int, pan: Float) {
        val angle = ((pan + 1f) * 0.5f) * (kotlin.math.PI.toFloat() * 0.5f)
        val leftGain = kotlin.math.cos(angle)
        val rightGain = kotlin.math.sin(angle)
        pluginProvider.stereoPlugin.setVoicePan(voiceIndex, leftGain, rightGain)
    }
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? =
        pluginProvider.getPlugin(pluginUri)?.getPortValue(symbol)

    // Automation Delegation
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {
        // Pre-activate voice on gate automation (REPL voices 8-11 start idle)
        if (controlId.startsWith("voice_gate_")) {
            val index = controlId.removePrefix("voice_gate_").toIntOrNull()
            if (index != null) {
                voiceManager.setVoiceIdle(index, false)
                nativeBridge.nativeSetVoiceActive(index, true)
            }
        }

        scheduleNativeAutomation(controlId, times, values, count)
    }

    override fun clearParameterAutomation(controlId: String) {
        when {
            controlId.startsWith("voice_gate_") -> {
                val index = controlId.removePrefix("voice_gate_").toIntOrNull()
                if (index != null) nativeBridge.nativeClearAutomation(0, index)
            }
            controlId.startsWith("voice_freq_") -> {
                val index = controlId.removePrefix("voice_freq_").toIntOrNull()
                if (index != null) nativeBridge.nativeClearAutomation(1, index)
            }
        }
    }

    /**
     * Forward automation paths to the C++ engine for sample-accurate playback.
     * The C++ audio thread steps through time/value paths at block boundaries,
     * converting Hz to MIDI notes internally for voice_freq targets.
     */
    private fun scheduleNativeAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int) {
        when {
            controlId.startsWith("voice_gate_") -> {
                val index = controlId.removePrefix("voice_gate_").toIntOrNull() ?: return
                nativeBridge.nativeSetAutomation(0, index, times, values, count) // 0 = VOICE_GATE
            }
            controlId.startsWith("voice_freq_") -> {
                val index = controlId.removePrefix("voice_freq_").toIntOrNull() ?: return
                nativeBridge.nativeSetAutomation(1, index, times, values, count) // 1 = VOICE_FREQ
            }
            else -> log.debug { "Native automation: unhandled controlId=$controlId" }
        }
    }

    // State Getters (Delegated)
    override fun getPeak(): Float = monitor.getPeak()
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
    }

    private fun setResonatorStructure(value: Float) {
        setPort(ResonatorSymbol.STRUCTURE, PortValue.FloatValue(value))
    }

    private fun setResonatorBrightness(value: Float) {
        setPort(ResonatorSymbol.BRIGHTNESS, PortValue.FloatValue(value))
    }

    private fun setResonatorDamping(value: Float) {
        setPort(ResonatorSymbol.DAMPING, PortValue.FloatValue(value))
    }

    private fun setResonatorPosition(value: Float) {
        setPort(ResonatorSymbol.POSITION, PortValue.FloatValue(value))
    }

    private fun strumResonator(frequency: Float) {
        pluginProvider.resonatorPlugin.strum(frequency)
    }

    // Drums getters
    override fun getDrumFrequency(type: Int): Float = pluginProvider.drumPlugin.getFrequency(type)
    override fun getDrumTone(type: Int): Float = pluginProvider.drumPlugin.getTone(type)
    override fun getDrumDecay(type: Int): Float = pluginProvider.drumPlugin.getDecay(type)
    override fun getDrumP4(type: Int): Float = pluginProvider.drumPlugin.getP4(type)
    override fun getDrumP5(type: Int): Float = pluginProvider.drumPlugin.getP5(type)

    // Quad delegations (with C++ forwarding)
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {
        // Quads receive raw indices (1=X1, 2=X2, 3=X3) from TriggerRouterPanel — no mapping needed.
        // (Drums use DrumTriggerSource enum ordinals 4/5/6, mapped in setDrumPitchSource.)
        voiceManager.setQuadPitchSource(quadIndex, sourceIndex)
        pluginProvider.fluxPlugin.setQuadPitchSource(quadIndex, sourceIndex)
    }
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {
        voiceManager.setQuadTriggerSource(quadIndex, sourceIndex)
        pluginProvider.fluxPlugin.setQuadTriggerSource(quadIndex, sourceIndex)
    }
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = voiceManager.setQuadEnvelopeTriggerMode(quadIndex, enabled)
    override fun getQuadPitchSource(quadIndex: Int) = voiceManager.getQuadPitchSource(quadIndex)
    override fun getQuadTriggerSource(quadIndex: Int) = voiceManager.getQuadTriggerSource(quadIndex)
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int) = voiceManager.getQuadEnvelopeTriggerMode(quadIndex)

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
        private const val LOOPER_URI = "org.balch.orpheus.plugins.looper"
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
            // V1.2 engines (engine2/ directory)
            0,  // VIRTUAL_ANALOG_VCF (C++ index 0)
            1,  // PHASE_DISTORTION (C++ index 1)
            2,  // SIX_OP_FM (C++ index 2; indices 3,4 are duplicate hardware slots, unused)
            5,  // WAVE_TERRAIN (C++ index 5)
            6,  // STRING_MACHINE (C++ index 6)
            7,  // CHIPTUNE (C++ index 7)
        )
    }
}
