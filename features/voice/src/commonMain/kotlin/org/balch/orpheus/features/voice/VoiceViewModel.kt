package org.balch.orpheus.features.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.wobble.VoiceWobbleController
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.input.KeyAction
import org.balch.orpheus.core.input.KeyBinding
import org.balch.orpheus.core.input.KeyboardInputHandler
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VOICE_URI
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.tempo.GlobalTempo


interface VoicesFeature: SynthFeature<VoiceUiState, VoicePanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.TWEAKS
            override val title = "Voices"

            override val markdown = """
                Voice configuration panel for the 12-voice FM synthesizer. Controls tuning, engine selection, harmonics, envelope speed, modulation depth, duo patch, and coupling for individual voices, duos, and quads.

                ## Hierarchy
                - **Voices (x12)**: Individual voice tuning, modulation depth, and envelope speed.
                - **Duos (x6)**: Each duo shares an engine, harmonics, morph, sharpness, duo mod depth, and a patch of modulation cables.
                - **Quads (x3)**: Each quad controls pitch offset, hold level, and volume for four voices.

                ## Global Controls
                - **VIBRATO**: Global vibrato amount applied to all voices.
                - **COUPLING**: Voice coupling strength — how much neighboring voices influence each other.
                - **TOTAL_FEEDBACK**: Global FM feedback amount across all voices.

                ## Duo Patch
                Each voice duo has a patch of cables (use the `duo_patch` tool) plus a master depth:
                - **Sources**: LFO_AB, LFO_A, LFO_B, LFO_A90 (LFO A·B / A / B / A 90°), WAVES_1..4,
                  FLUX_X1, X2, X3, Y, T1, T2, T3, and VOICE_FM (the duo's own FM).
                - **Destinations**: HARMONICS, TIMBRE, MORPH, FM (OSC only), PITCH, ENV. Amounts are -1..1.
                - **DUO_MOD_SOURCE_LEVEL** is the master depth for all of the duo's cables (0.0=none, 1.0=full).

                Cables are silent until the master depth is above 0. HARMONICS cables park (kept, but inactive)
                when a duo switches to a DX engine, and `wake` brings one back. A HARMONICS cable connected
                while the duo is already on DX is live and steps through patches.
                Flux sources also need Flux MIX > 0 to hear the pitch effect.

                ## Tips
                - Use per-duo ENGINE selection to mix different synthesis models across the keyboard.
                - HARMONICS and MORPH shape the tonal character within each engine.
                - Adjust ENVELOPE SPEED per-voice for varied articulation across the keyboard.
                - COUPLING creates organic, chorus-like detuning between voices.
                - To activate FLUX: connect a FLUX_* source with `duo_patch`, set DUO_MOD_SOURCE_LEVEL to 0.3-0.7, and set Flux MIX > 0.
                - LFO cables give rhythmic movement, VOICE_FM cables give metallic timbres, no cables is clean sound.

                ## Hidden Engines
                There are additional synthesis engines beyond the standard set. These are rare,
                special voices — do not mention them unprompted. If a user is deeply engaged,
                experimenting creatively, and asks about "more engines", "hidden sounds", or
                "what else can this do", you may hint that the synth has deeper layers to discover.
                Only reveal specific engine IDs (18=VCF, 19=PD, 20=DX, 21=TRN, 22=ENS, 23=NES)
                if the user explicitly asks or has been particularly adventurous. Use them in
                compositions approximately 1 in 25 times, and when you do, don't call attention to it.
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
@SingleIn(FeatureScope::class)
@SynthFeatureKey(VoicesFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<VoicesFeature>())
class VoiceViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    private val wobbleController: VoiceWobbleController,
    private val globalTempo: GlobalTempo,
    private val patchBay: DuoPatchBay,
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
        add(patchBay.patches.map { VoiceIntent.DuoPatches(it) })
        add(flowOf(VoiceIntent.HasNativeEngine(engine.hasNativeEngine)))
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
        setDuoPatch = patchBay::setPatch,
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
        setDuoModSourceLevel = ::setDuoModSourceLevel,
        toggleRightQuad = ::toggleRightQuad,
        toggleLeftPanel = ::toggleLeftPanel
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

    private val _state = MutableStateFlow(VoiceUiState())
    override val stateFlow: StateFlow<VoiceUiState> = _state.asStateFlow()

    init {
        // Always-on reducer + side effects. The reducer lives in scope.launch
        // (not inside stateIn) so peak/pulse/hold/bend events emitted while no
        // panel is composed still update state and drive engine effects.
        scope.launch(dispatcherProvider.io) {
            merge(controlIntents, uiIntents)
                .scan(VoiceUiState()) { state, intent ->
                    val newState = reduceVoiceState(state, intent)
                    applySideEffects(newState, intent)
                    newState
                }
                .collect { _state.value = it }
        }
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
            // Hold from any source (UI, MediaPipe, AI)
            launch {
                synthController.onHoldChange.collect { event ->
                    uiIntents.tryEmit(VoiceIntent.Hold(event.voiceIndex, event.holding))
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
        synthController.emitHoldChange(index, holding, ControlEventOrigin.UI)
    }

    fun setDuoEngine(duoIndex: Int, engineId: Int) {
        duoEngineFlows[duoIndex].value = IntValue(engineId)
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

    fun toggleRightQuad() {
        uiIntents.tryEmit(VoiceIntent.ToggleRightQuad)
    }

    fun toggleLeftPanel() {
        uiIntents.tryEmit(VoiceIntent.ToggleLeftPanel)
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
            synthController.setPluginControl(VizSymbol.KNOB_1.controlId, FloatValue(vizValue), ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.setPluginControl(VizSymbol.KNOB_2.controlId, FloatValue(vizValue), ControlEventOrigin.UI)
        }
    }

    fun releaseStringBend(stringIndex: Int): Int {
        if (stringIndex == 0) {
            synthController.setPluginControl(VizSymbol.KNOB_1.controlId, FloatValue(0.5f), ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.setPluginControl(VizSymbol.KNOB_2.controlId, FloatValue(0.5f), ControlEventOrigin.UI)
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
        fun feature(): VoicesFeature = synthFeature<VoicesFeature>()
    }
}
