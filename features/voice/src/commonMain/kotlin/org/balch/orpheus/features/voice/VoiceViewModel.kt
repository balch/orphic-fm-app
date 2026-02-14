package org.balch.orpheus.features.voice

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
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
import org.balch.orpheus.core.SynthFeature
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
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.tempo.GlobalTempo


interface VoicesFeature: SynthFeature<VoiceUiState, VoicePanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly
}
/**
 * ViewModel for voice management.
 *
 * Uses MVI pattern with SynthController.controlFlow() for port-based engine interactions.
 * Keeps SynthEngine dependency for non-port operations (gate, hold, wobble, bend, slide, peak).
 */
@Inject
@ViewModelKey(VoiceViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class VoiceViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    private val wobbleController: VoiceWobbleController,
    private val globalTempo: GlobalTempo,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), VoicesFeature {

    // ═══════════════════════════════════════════════════════════
    // CONTROL FLOWS
    // ═══════════════════════════════════════════════════════════

    // Per-voice (×12)
    private val tuneFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.tune(i).controlId) }
    private val modDepthFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.modDepth(i).controlId) }
    private val envSpeedFlows = Array(12) { i -> synthController.controlFlow(VoiceSymbol.envSpeed(i).controlId) }

    // Per-pair (×6)
    private val pairSharpnessFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.pairSharpness(i).controlId) }
    private val duoModSourceFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.duoModSource(i).controlId) }
    private val pairEngineFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.pairEngine(i).controlId) }
    private val pairHarmonicsFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.pairHarmonics(i).controlId) }
    private val pairMorphFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.pairMorph(i).controlId) }
    private val pairModDepthFlows = Array(6) { i -> synthController.controlFlow(VoiceSymbol.pairModDepth(i).controlId) }

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
            add(pairSharpnessFlows[i].map { VoiceIntent.PairSharpness(i, it.asFloat()) })
            add(duoModSourceFlows[i].map {
                val sources = ModSource.entries
                val srcIndex = it.asInt().coerceIn(0, sources.size - 1)
                VoiceIntent.DuoModSource(i, sources[srcIndex])
            })
            add(pairEngineFlows[i].map { VoiceIntent.PairEngine(i, it.asInt()) })
            add(pairHarmonicsFlows[i].map { VoiceIntent.PairHarmonics(i, it.asFloat()) })
            add(pairMorphFlows[i].map { VoiceIntent.PairMorph(i, it.asFloat()) })
            add(pairModDepthFlows[i].map { VoiceIntent.PairModDepth(i, it.asFloat()) })
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
        setPairSharpness = ::setPairSharpness,
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
        setPairEngine = ::setPairEngine,
        setPairHarmonics = ::setPairHarmonics,
        setPairMorph = ::setPairMorph,
        setPairModDepth = ::setPairModDepth
    )

    override val stateFlow: StateFlow<VoiceUiState> =
        merge(controlIntents, uiIntents)
            .scan(VoiceUiState()) { state, intent ->
                val newState = reduceVoiceState(state, intent)
                applySideEffects(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = VoiceUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
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
                    .filter { it.controlId.startsWith(VOICE_URI) && it.controlId.contains("pair_engine") }
                    .collect { event ->
                        val pairIndex = event.controlId.substringAfterLast("_").toIntOrNull()
                        if (pairIndex != null) {
                            uiIntents.tryEmit(VoiceIntent.AiVoiceEngineHighlight(pairIndex, true))
                            delay(2000)
                            uiIntents.tryEmit(VoiceIntent.AiVoiceEngineHighlight(pairIndex, false))
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

    fun setPairSharpness(pairIndex: Int, value: Float) {
        pairSharpnessFlows[pairIndex].value = FloatValue(value)
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

    fun setPairEngine(pairIndex: Int, engineOrdinal: Int) {
        pairEngineFlows[pairIndex].value = IntValue(engineOrdinal)
    }

    fun setPairHarmonics(pairIndex: Int, value: Float) {
        pairHarmonicsFlows[pairIndex].value = FloatValue(value)
    }

    fun setPairMorph(pairIndex: Int, value: Float) {
        pairMorphFlows[pairIndex].value = FloatValue(value)
    }

    fun setPairModDepth(pairIndex: Int, value: Float) {
        pairModDepthFlows[pairIndex].value = FloatValue(value)
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
        fun feature(): VoicesFeature = synthViewModel<VoiceViewModel, VoicesFeature>()
    }
}
