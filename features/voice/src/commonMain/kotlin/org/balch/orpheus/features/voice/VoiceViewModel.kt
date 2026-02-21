package org.balch.orpheus.features.voice

import androidx.compose.runtime.Composable
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.input.KeyAction
import org.balch.orpheus.core.input.KeyBinding
import org.balch.orpheus.core.input.KeyboardInputHandler
import androidx.compose.ui.input.key.Key
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.wobble.VoiceWobbleController
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.tempo.GlobalTempo


interface VoicesFeature: SynthFeature<VoiceUiState, VoicePanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.TWEAKS
            override val title = "Voices"

            override val markdown = """
                Voice configuration panel for the 12-voice FM synthesizer. Controls tuning, engine selection, harmonics, envelope speed, modulation depth, duo modulation source, and coupling for individual voices, duos, and quads.

                ## Hierarchy
                - **Voices (x12)**: Individual voice tuning, modulation depth, and envelope speed.
                - **Duos (x6)**: Each duo shares an engine, harmonics, morph, sharpness, duo mod depth, and duo modulation source.
                - **Quads (x3)**: Each quad controls pitch offset, hold level, and volume for four voices.

                ## Global Controls
                - **VIBRATO**: Global vibrato amount applied to all voices.
                - **COUPLING**: Voice coupling strength — how much neighboring voices influence each other.
                - **TOTAL_FEEDBACK**: Global FM feedback amount across all voices.

                ## Duo Modulation Source
                Each voice duo has TWO controls that work together:
                - **DUO_MOD_SOURCE** selects WHICH effect: 0=FM, 1=OFF, 2=LFO, 3=FLUX.
                - **DUO_MOD_SOURCE_LEVEL** controls HOW MUCH of that effect is applied (0.0=none, 1.0=full).

                Always set BOTH when activating a mod source. Setting the source without depth means no audible effect.

                Mod source types:
                - **0 = FM**: Voice FM synthesis modulation between the two voices in the duo.
                - **1 = OFF**: No modulation effect applied.
                - **2 = LFO**: Low-frequency oscillator modulation for rhythmic movement.
                - **3 = FLUX**: Flux random sequencer — generates evolving pitch sequences and gates. Also requires Flux MIX > 0 to hear pitch effect.

                ## Tips
                - Use per-duo ENGINE selection to mix different synthesis models across the keyboard.
                - HARMONICS and MORPH shape the tonal character within each engine.
                - Adjust ENVELOPE SPEED per-voice for varied articulation across the keyboard.
                - COUPLING creates organic, chorus-like detuning between voices.
                - To activate FLUX: set DUO_MOD_SOURCE to 3, set DUO_MOD_SOURCE_LEVEL to 0.3-0.7, and set Flux MIX > 0.
                - Use LFO (2) for rhythmic tremolo/vibrato, FM (0) for metallic timbres, OFF (1) for clean sound.
            """.trimIndent()

            override val portControlKeys = buildMap {
                // Globals
                put(VoiceSymbol.VIBRATO.controlId.key, "Global vibrato amount")
                put(VoiceSymbol.COUPLING.controlId.key, "Voice coupling strength")
                put(VoiceSymbol.TOTAL_FEEDBACK.controlId.key, "Global FM feedback amount")
                // Per-voice tuning (x12)
                for (i in 0 until 12) {
                    put(VoiceSymbol.tune(i).controlId.key, "Tuning offset for voice $i")
                }
                // Per-duo engine (x6)
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoEngine(i).controlId.key, "Synthesis engine for duo $i")
                }
                // Per-duo harmonics (x6)
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoHarmonics(i).controlId.key, "Harmonics for duo $i")
                }
                // Per-duo morph (x6)
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoMorph(i).controlId.key, "Morph / timbre for duo $i")
                }
                // Per-duo sharpness (x6)
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoSharpness(i).controlId.key, "Sharpness for duo $i")
                }
                // Per-duo mod source level (x6)
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoModSourceLevel(i).controlId.key, "Mod source level for duo $i")
                }
                // Per-duo mod source (x6): 0=FM, 1=OFF, 2=LFO, 3=FLUX
                for (i in 0 until 6) {
                    put(VoiceSymbol.duoModSource(i).controlId.key, "Duo modulation source for duo $i (0=FM, 1=OFF, 2=LFO, 3=FLUX)")
                }
                // Per-voice mod depth (x12)
                for (i in 0 until 12) {
                    put(VoiceSymbol.modDepth(i).controlId.key, "Modulation depth for voice $i")
                }
                // Per-voice envelope speed (x12)
                for (i in 0 until 12) {
                    put(VoiceSymbol.envSpeed(i).controlId.key, "Envelope speed for voice $i")
                }
                // Per-quad pitch (x3)
                for (i in 0 until 3) {
                    put(VoiceSymbol.quadPitch(i).controlId.key, "Pitch offset for quad $i")
                }
                // Per-quad hold (x3)
                for (i in 0 until 3) {
                    put(VoiceSymbol.quadHold(i).controlId.key, "Hold level for quad $i")
                }
                // Per-quad volume (x3)
                for (i in 0 until 3) {
                    put(VoiceSymbol.quadVolume(i).controlId.key, "Volume for quad $i")
                }
            }

        }
    }
}
/**
 * ViewModel for voice management.
 *
 * Uses MVI pattern with SynthController.controlFlow() for port-based engine interactions.
 * Keeps SynthEngine dependency for non-port operations (gate, hold, wobble, bend, slide, peak).
 */
@Inject
@ClassKey(VoiceViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class VoiceViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    private val wobbleController: VoiceWobbleController,
    private val globalTempo: GlobalTempo,
    dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope
) : VoicesFeature {

    // ═══════════════════════════════════════════════════════════
    // CONTROL FLOWS
    // ═══════════════════════════════════════════════════════════

    // Per-voice (×12)
    private val tuneFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.tune(i).controlId) }
    private val modDepthFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.modDepth(i).controlId) }
    private val envSpeedFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.envSpeed(i).controlId) }

    // Per-duo (×6)
    private val duoSharpnessFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoSharpness(i).controlId) }
    private val duoModSourceFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoModSource(i).controlId) }
    private val duoEngineFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoEngine(i).controlId) }
    private val duoHarmonicsFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoHarmonics(i).controlId) }
    private val duoMorphFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoMorph(i).controlId) }
    private val duoModSourceLevelFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoModSourceLevel(i).controlId) }

    // Per-quad (×3)
    private val quadPitchFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadPitch(i).controlId) }
    private val quadHoldFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadHold(i).controlId) }
    private val quadVolumeFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadVolume(i).controlId) }
    private val quadTriggerSourceFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadTriggerSource(i).controlId) }
    private val quadPitchSourceFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadPitchSource(i).controlId) }
    private val quadEnvTriggerModeFlows = Array(3) { i -> synthController.controlFlow(VoiceSymbol.quadEnvTriggerMode(i).controlId) }

    // Global
    private val fmStructureFlow = synthController.controlFlow(VoiceSymbol.FM_STRUCTURE_CROSS_QUAD.controlId)
    private val totalFeedbackFlow = synthController.controlFlow(VoiceSymbol.TOTAL_FEEDBACK.controlId)
    private val vibratoFlow = synthController.controlFlow(VoiceSymbol.VIBRATO.controlId)
    private val couplingFlow = synthController.controlFlow(VoiceSymbol.COUPLING.controlId)
    private val masterVolFlow = synthController.controlFlow(StereoSymbol.MASTER_VOL.controlId)

    // ═══════════════════════════════════════════════════════════
    // INTENTS
    // ═══════════════════════════════════════════════════════════

    // UI-only intents (non-port state: pulse, hold, peak, bend, bpm)
    private val uiIntents = MutableSharedFlow<VoiceIntent>(extraBufferCapacity = 256)

    // Port-based control changes -> intents
    private val controlIntents: Flow<VoiceIntent> = buildList<Flow<VoiceIntent>> {
        for (i in 0 until 12) {
            add(tuneFlows[i].map { VoiceIntent.Tune(i, it.asFloat()) })
            add(modDepthFlows[i].map { VoiceIntent.ModDepth(i, it.asFloat()) })
            add(envSpeedFlows[i].map { VoiceIntent.EnvelopeSpeed(i, it.asFloat()) })
        }
        for (i in 0 until 6) {
            add(duoSharpnessFlows[i].map { VoiceIntent.DuoSharpness(i, it.asFloat()) })
            add(duoModSourceFlows[i].map {
                val sources = ModSource.entries
                val srcIndex = it.asInt().coerceIn(0, sources.size - 1)
                VoiceIntent.DuoModSource(i, sources[srcIndex])
            })
            add(duoEngineFlows[i].map { VoiceIntent.DuoEngine(i, it.asInt()) })
            add(duoHarmonicsFlows[i].map { VoiceIntent.DuoHarmonics(i, it.asFloat()) })
            add(duoMorphFlows[i].map { VoiceIntent.DuoMorph(i, it.asFloat()) })
            add(duoModSourceLevelFlows[i].map { VoiceIntent.DuoModSourceLevel(i, it.asFloat()) })
        }
        for (i in 0 until 3) {
            add(quadPitchFlows[i].map { VoiceIntent.QuadPitch(i, it.asFloat()) })
            add(quadHoldFlows[i].map { VoiceIntent.QuadHold(i, it.asFloat()) })
            add(quadVolumeFlows[i].map { VoiceIntent.QuadVolume(i, it.asFloat()) })
            add(quadTriggerSourceFlows[i].map { VoiceIntent.QuadTriggerSource(i, it.asInt()) })
            add(quadPitchSourceFlows[i].map { VoiceIntent.QuadPitchSource(i, it.asInt()) })
            add(quadEnvTriggerModeFlows[i].map { VoiceIntent.QuadEnvelopeTriggerMode(i, it.asBoolean()) })
        }
        add(fmStructureFlow.map { VoiceIntent.FmStructure(it.asBoolean()) })
        add(totalFeedbackFlow.map { VoiceIntent.TotalFeedback(it.asFloat()) })
        add(vibratoFlow.map { VoiceIntent.Vibrato(it.asFloat()) })
        add(couplingFlow.map { VoiceIntent.VoiceCoupling(it.asFloat()) })
        add(masterVolFlow.map { VoiceIntent.MasterVolume(it.asFloat()) })
    }.merge()

    override val actions: VoicePanelActions = VoicePanelActions(
        setMasterVolume = masterVolFlow.floatSetter(),
        setVibrato = vibratoFlow.floatSetter(),
        setVoiceTune = ::setVoiceTune,
        setVoiceModDepth = ::setVoiceModDepth,
        setDuoModDepth = ::setDuoModDepth,
        setDuoSharpness = ::setDuoSharpness,
        setVoiceEnvelopeSpeed = ::setVoiceEnvelopeSpeed,
        pulseStart = ::pulseStart,
        pulseEnd = ::pulseEnd,
        setHold = ::setHold,
        setDuoModSource = ::setDuoModSource,
        setQuadPitch = ::setQuadPitch,
        setQuadHold = ::setQuadHold,
        setFmStructure = fmStructureFlow.boolSetter(),
        setTotalFeedback = totalFeedbackFlow.floatSetter(),
        setVoiceCoupling = couplingFlow.floatSetter(),
        wobblePulseStart = ::wobblePulseStart,
        wobbleMove = ::wobbleMove,
        wobblePulseEnd = ::wobblePulseEnd,
        setBend = ::setBend,
        releaseBend = ::releaseBend,
        setStringBend = ::setStringBend,
        releaseStringBend = ::releaseStringBend,
        setSlideBar = ::setSlideBar,
        releaseSlideBar = ::releaseSlideBar,
        setBpm = ::setBpm,
        setQuadTriggerSource = ::setQuadTriggerSource,
        setQuadPitchSource = ::setQuadPitchSource,
        setQuadEnvelopeTriggerMode = ::setQuadEnvelopeTriggerMode,
        setDuoEngine = ::setDuoEngine,
        setDuoHarmonics = ::setDuoHarmonics,
        setDuoMorph = ::setDuoMorph,
        setDuoModSourceLevel = ::setDuoModSourceLevel
    )

    override val keyBindings: List<KeyBinding> = buildList {
        // Voice gates (A-K → voices 0-7)
        val voiceKeys = listOf(Key.A, Key.S, Key.D, Key.F, Key.G, Key.H, Key.J, Key.K)
        voiceKeys.forEachIndexed { i, key ->
            add(KeyBinding(key, "${('A' + i)}", "Trigger Voice ${i + 1}",
                action = KeyAction.Gate(id = i, onDown = { pulseStart(i) }, onUp = { pulseEnd(i) })))
        }
        // Tune triggers (1-8, shift-aware for coarse/fine)
        val tuneKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)
        tuneKeys.forEachIndexed { i, key ->
            add(KeyBinding(key, "${i + 1}", "Tune Voice ${i + 1}",
                action = KeyAction.Trigger { isShiftPressed ->
                    val currentTune = stateFlow.value.voiceStates[i].tune
                    val delta = KeyboardInputHandler.getTuneDelta(isShiftPressed)
                    setVoiceTune(i, (currentTune + delta).coerceIn(0f, 1f))
                    true
                }))
        }
        // Octave shift (Z/X)
        add(KeyBinding(Key.Z, "Z", "Octave down",
            action = KeyAction.Trigger { KeyboardInputHandler.handleOctaveKey(Key.Z); true }))
        add(KeyBinding(Key.X, "X", "Octave up",
            action = KeyAction.Trigger { KeyboardInputHandler.handleOctaveKey(Key.X); true }))
    }

    override val stateFlow: StateFlow<VoiceUiState> =
        merge(controlIntents, uiIntents)
            .scan(VoiceUiState()) { state, intent ->
                val newState = reduceVoiceState(state, intent)
                applySideEffects(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = VoiceUiState()
            )

    init {
        scope.launch(dispatcherProvider.io) {
            // Pulse events
            launch {
                synthController.onPulseStart.collect { voiceIndex ->
                    uiIntents.tryEmit(VoiceIntent.PulseStart(voiceIndex))
                }
            }
            launch {
                synthController.onPulseEnd.collect { voiceIndex ->
                    uiIntents.tryEmit(VoiceIntent.PulseEnd(voiceIndex))
                }
            }
            // Peak monitoring
            launch {
                engine.peakFlow.collect { peak ->
                    uiIntents.tryEmit(VoiceIntent.PeakLevel(peak))
                }
            }
            // Bend from AI
            launch {
                synthController.onBendChange.collect { amount ->
                    setBend(amount)
                }
            }
            // Bend display
            launch {
                engine.bendFlow.collect { bendAmount ->
                    uiIntents.tryEmit(VoiceIntent.BendPosition(bendAmount))
                }
            }
            // GlobalTempo
            launch {
                globalTempo.bpm.collect { bpm ->
                    uiIntents.tryEmit(VoiceIntent.SetBpm(bpm))
                }
            }
            // AI engine change highlights
            launch {
                synthController.onControlChange
                    .filter { it.origin == ControlEventOrigin.AI }
                    .filter { it.controlId.startsWith(VOICE_URI) && it.controlId.contains("duo_engine") }
                    .collect { event ->
                        val duoIndex = event.controlId.substringAfterLast("_").toIntOrNull()
                        if (duoIndex != null) {
                            uiIntents.tryEmit(VoiceIntent.AiVoiceEngineHighlight(duoIndex, true))
                            delay(2000)
                            uiIntents.tryEmit(VoiceIntent.AiVoiceEngineHighlight(duoIndex, false))
                        }
                    }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SIDE EFFECTS (non-port operations only; port-based handled by controlFlows)
    // ═══════════════════════════════════════════════════════════

    private fun applySideEffects(state: VoiceUiState, intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.PulseStart -> engine.setVoiceGate(intent.index, true)
            is VoiceIntent.PulseEnd -> engine.setVoiceGate(intent.index, false)

            is VoiceIntent.Hold -> {
                if (intent.holding) {
                    // Hold level based on envelope speed: Speed 0 (fast) -> 0.7, Speed 1 (slow) -> 1.0
                    val envSpeed = state.voiceEnvelopeSpeeds[intent.index]
                    val holdLevel = 0.7f + (envSpeed * 0.3f)
                    engine.setVoiceHold(intent.index, holdLevel)
                } else {
                    engine.setVoiceHold(intent.index, 0f)
                    if (!state.voiceStates[intent.index].pulse) engine.setVoiceGate(intent.index, false)
                }
            }

            is VoiceIntent.EnvelopeSpeed -> {
                // If this voice is currently holding, update its hold level based on new speed
                if (state.voiceStates[intent.index].isHolding) {
                    val holdLevel = 0.7f + (intent.value * 0.3f)
                    engine.setVoiceHold(intent.index, holdLevel)
                }
            }

            is VoiceIntent.FmStructure -> {
                // Re-apply duoModSources with VOICE_FM routing (depends on structure flag)
                state.duoModSources.forEachIndexed { index, source ->
                    if (source == ModSource.VOICE_FM)
                        engine.setDuoModSource(index, source)
                }
            }

            else -> { /* Port-based side effects handled by controlFlows */ }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setVoiceTune(index: Int, value: Float) {
        tuneFlows[index].value = FloatValue(value)
    }

    fun setVoiceModDepth(index: Int, value: Float) {
        modDepthFlows[index].value = FloatValue(value)
    }

    fun setDuoModDepth(duoIndex: Int, value: Float) {
        modDepthFlows[duoIndex * 2].value = FloatValue(value)
        modDepthFlows[duoIndex * 2 + 1].value = FloatValue(value)
    }

    fun setDuoSharpness(duoIndex: Int, value: Float) {
        duoSharpnessFlows[duoIndex].value = FloatValue(value)
    }

    fun setVoiceEnvelopeSpeed(index: Int, value: Float) {
        envSpeedFlows[index].value = FloatValue(value)
    }

    fun pulseStart(index: Int) {
        engine.resetStringBenders()
        synthController.emitPulseStart(index)
    }

    fun pulseEnd(index: Int) {
        synthController.emitPulseEnd(index)
    }

    fun setHold(index: Int, holding: Boolean) {
        uiIntents.tryEmit(VoiceIntent.Hold(index, holding))
    }

    fun setDuoModSource(index: Int, source: ModSource) {
        duoModSourceFlows[index].value = IntValue(source.ordinal)
    }

    fun setDuoEngine(duoIndex: Int, engineOrdinal: Int) {
        duoEngineFlows[duoIndex].value = IntValue(engineOrdinal)
    }

    fun setDuoHarmonics(duoIndex: Int, value: Float) {
        duoHarmonicsFlows[duoIndex].value = FloatValue(value)
    }

    fun setDuoMorph(duoIndex: Int, value: Float) {
        duoMorphFlows[duoIndex].value = FloatValue(value)
    }

    fun setDuoModSourceLevel(duoIndex: Int, value: Float) {
        duoModSourceLevelFlows[duoIndex].value = FloatValue(value)
    }

    fun setQuadPitch(index: Int, value: Float) {
        quadPitchFlows[index].value = FloatValue(value)
    }

    fun setQuadHold(index: Int, value: Float) {
        quadHoldFlows[index].value = FloatValue(value)
    }

    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {
        quadTriggerSourceFlows[quadIndex].value = IntValue(sourceIndex)
    }

    fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {
        quadPitchSourceFlows[quadIndex].value = IntValue(sourceIndex)
    }

    fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) {
        quadEnvTriggerModeFlows[quadIndex].value = BoolValue(enabled)
    }

    // ═══════════════════════════════════════════════════════════
    // WOBBLE METHODS
    // ═══════════════════════════════════════════════════════════

    fun wobblePulseStart(index: Int, x: Float, y: Float) {
        wobbleController.onPulseStart(index, x, y)
        engine.setVoiceWobble(index, 0f, wobbleController.config.value.range)
    }

    fun wobbleMove(index: Int, x: Float, y: Float) {
        val wobbleOffset = wobbleController.onPointerMove(index, x, y)
        val range = wobbleController.config.value.range
        engine.setVoiceWobble(index, wobbleOffset, range)
    }

    fun wobblePulseEnd(index: Int) {
        wobbleController.onPulseEnd(index)
        engine.setVoiceWobble(index, 0f, 0f)
    }

    // ═══════════════════════════════════════════════════════════
    // BENDER METHODS
    // ═══════════════════════════════════════════════════════════

    fun setBend(amount: Float) {
        engine.resetStringBenders()
        engine.setBend(amount)
        val vizKnob2Value = (amount + 1f) / 2f
        synthController.emitControlChange(
            VizSymbol.KNOB_2.controlId.key,
            vizKnob2Value,
            ControlEventOrigin.UI
        )
    }

    fun releaseBend() {
        engine.setBend(0f)
        synthController.emitControlChange(VizSymbol.KNOB_2.controlId.key, 0.5f, ControlEventOrigin.UI)
    }

    // ═══════════════════════════════════════════════════════════
    // PER-STRING BENDER METHODS
    // ═══════════════════════════════════════════════════════════

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        engine.setStringBend(stringIndex, bendAmount, voiceMix)
        val vizValue = (bendAmount + 1f) / 2f
        if (stringIndex == 0) {
            synthController.emitControlChange(VizSymbol.KNOB_1.controlId.key, vizValue, ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.emitControlChange(VizSymbol.KNOB_2.controlId.key, vizValue, ControlEventOrigin.UI)
        }
    }

    fun releaseStringBend(stringIndex: Int): Int {
        if (stringIndex == 0) {
            synthController.emitControlChange(VizSymbol.KNOB_1.controlId.key, 0.5f, ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.emitControlChange(VizSymbol.KNOB_2.controlId.key, 0.5f, ControlEventOrigin.UI)
        }
        return engine.releaseStringBend(stringIndex)
    }

    // ═══════════════════════════════════════════════════════════
    // SLIDE BAR METHODS
    // ═══════════════════════════════════════════════════════════

    fun setSlideBar(yPosition: Float, xPosition: Float) {
        engine.setSlideBar(yPosition, xPosition)
    }

    fun releaseSlideBar() {
        engine.releaseSlideBar()
    }

    fun setBpm(bpm: Double) {
        globalTempo.setBpm(bpm)
    }

    companion object {
        fun previewFeature(state: VoiceUiState = VoiceUiState()) =
            object : VoicesFeature {
                override val stateFlow: StateFlow<VoiceUiState> = MutableStateFlow(state)
                override val actions: VoicePanelActions = VoicePanelActions.EMPTY
            }

        @Composable
        fun feature(): VoicesFeature = synthFeature<VoiceViewModel, VoicesFeature>()
    }
}
