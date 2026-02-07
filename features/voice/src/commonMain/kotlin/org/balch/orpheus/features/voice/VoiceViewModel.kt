package org.balch.orpheus.features.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.audio.wobble.VoiceWobbleController
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.tempo.GlobalTempo

@Immutable
data class VoiceUiState(
    val voiceStates: List<VoiceState> =
        List(12) { index -> VoiceState(index = index, tune = DEFAULT_TUNINGS.getOrElse(index) { 0.5f }) },
    val voiceModDepths: List<Float> = List(12) { 0.0f },
    val pairSharpness: List<Float> = List(6) { 0.0f },
    val voiceEnvelopeSpeeds: List<Float> = List(12) { 0.0f },
    val duoModSources: List<ModSource> = List(6) { ModSource.OFF },
    val quadGroupPitches: List<Float> = List(3) { 0.5f },
    val quadGroupHolds: List<Float> = List(3) { 0.0f },
    val quadGroupVolumes: List<Float> = List(3) { 1.0f },
    val fmStructureCrossQuad: Boolean = false,
    val totalFeedback: Float = 0.0f,
    val vibrato: Float = 0.0f,
    val voiceCoupling: Float = 0.0f,
    val masterVolume: Float = 0.7f,
    val peakLevel: Float = 0.0f,
    val bendPosition: Float = 0.0f,
    val bpm: Double = 120.0,
    val pairEngines: List<Int> = List(6) { 0 },
    val pairHarmonics: List<Float> = List(6) { 0.5f },
    val quadTriggerSources: List<Int> = List(3) { 0 },
    val quadPitchSources: List<Int> = List(3) { 0 },
    val quadEnvelopeTriggerModes: List<Boolean> = listOf(false, false, false)
) {
    companion object {
        val DEFAULT_TUNINGS = listOf(0.20f, 0.27f, 0.34f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f, 0.75f, 0.82f, 0.89f, 0.96f)
    }
}

/** User intents for voice management. */
private sealed interface VoiceIntent {
    // Voice-level intents
    data class Tune(val index: Int, val value: Float) : VoiceIntent
    data class ModDepth(val index: Int, val value: Float) : VoiceIntent
    data class EnvelopeSpeed(val index: Int, val value: Float) : VoiceIntent
    data class PulseStart(val index: Int) : VoiceIntent
    data class PulseEnd(val index: Int) : VoiceIntent
    data class Hold(val index: Int, val holding: Boolean) : VoiceIntent

    // Pair-level intents
    data class PairSharpness(val pairIndex: Int, val value: Float) : VoiceIntent
    data class DuoModSource(val pairIndex: Int, val source: ModSource) : VoiceIntent
    data class PairEngine(val pairIndex: Int, val engineOrdinal: Int) : VoiceIntent
    data class PairHarmonics(val pairIndex: Int, val value: Float) : VoiceIntent

    // Quad-level intents
    data class QuadPitch(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadHold(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadVolume(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadTriggerSource(val quadIndex: Int, val sourceIndex: Int) : VoiceIntent
    data class QuadPitchSource(val quadIndex: Int, val sourceIndex: Int) : VoiceIntent
    data class QuadEnvelopeTriggerMode(val quadIndex: Int, val enabled: Boolean) : VoiceIntent

    // Global intents
    data class FmStructure(val crossQuad: Boolean) : VoiceIntent
    data class TotalFeedback(val value: Float) : VoiceIntent
    data class Vibrato(val value: Float) : VoiceIntent
    data class VoiceCoupling(val value: Float) : VoiceIntent
    data class MasterVolume(val value: Float) : VoiceIntent
    data class PeakLevel(val value: Float) : VoiceIntent
    data class BendPosition(val value: Float) : VoiceIntent
    data class SetBpm(val value: Double) : VoiceIntent
}


typealias VoicesFeature = SynthFeature<VoiceUiState, VoicePanelActions>
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
        setPairHarmonics = ::setPairHarmonics
    )

    override val stateFlow: StateFlow<VoiceUiState> =
        merge(controlIntents, uiIntents)
            .scan(VoiceUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applySideEffects(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
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
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: VoiceUiState, intent: VoiceIntent): VoiceUiState =
        when (intent) {
            is VoiceIntent.Tune ->
                state.withVoice(intent.index) { it.copy(tune = intent.value) }

            is VoiceIntent.ModDepth -> state.withModDepth(intent.index, intent.value)
            is VoiceIntent.EnvelopeSpeed ->
                state.withEnvelopeSpeed(intent.index, intent.value)

            is VoiceIntent.PulseStart ->
                state.withVoice(intent.index) { it.copy(pulse = true) }

            is VoiceIntent.PulseEnd ->
                state.withVoice(intent.index) { it.copy(pulse = false) }

            is VoiceIntent.Hold ->
                state.withVoice(intent.index) { it.copy(isHolding = intent.holding) }

            is VoiceIntent.PairSharpness ->
                state.withPairSharpness(intent.pairIndex, intent.value)

            is VoiceIntent.DuoModSource ->
                state.withDuoModSource(intent.pairIndex, intent.source)

            is VoiceIntent.PairEngine ->
                state.withPairEngine(intent.pairIndex, intent.engineOrdinal)

            is VoiceIntent.PairHarmonics ->
                state.withPairHarmonics(intent.pairIndex, intent.value)

            is VoiceIntent.QuadPitch ->
                state.withQuadPitch(intent.quadIndex, intent.value)

            is VoiceIntent.QuadHold ->
                state.withQuadHold(intent.quadIndex, intent.value)

            is VoiceIntent.QuadVolume ->
                state.withQuadVolume(intent.quadIndex, intent.value)

            is VoiceIntent.QuadTriggerSource ->
                state.withQuadTriggerSource(intent.quadIndex, intent.sourceIndex)

            is VoiceIntent.QuadPitchSource ->
                state.withQuadPitchSource(intent.quadIndex, intent.sourceIndex)

            is VoiceIntent.QuadEnvelopeTriggerMode ->
                state.copy(quadEnvelopeTriggerModes = state.quadEnvelopeTriggerModes.toMutableList().also { it[intent.quadIndex] = intent.enabled })

            is VoiceIntent.FmStructure ->
                state.copy(fmStructureCrossQuad = intent.crossQuad)

            is VoiceIntent.TotalFeedback ->
                state.copy(totalFeedback = intent.value)

            is VoiceIntent.Vibrato ->
                state.copy(vibrato = intent.value)

            is VoiceIntent.VoiceCoupling ->
                state.copy(voiceCoupling = intent.value)

            is VoiceIntent.MasterVolume ->
                state.copy(masterVolume = intent.value)

            is VoiceIntent.PeakLevel ->
                state.copy(peakLevel = intent.value)

            is VoiceIntent.BendPosition ->
                state.copy(bendPosition = intent.value)

            is VoiceIntent.SetBpm ->
                state.copy(bpm = intent.value)
        }

    // Helper extensions for cleaner state transformations
    private fun VoiceUiState.withVoice(index: Int, transform: (VoiceState) -> VoiceState) =
        copy(voiceStates = voiceStates.mapIndexed { i, v -> if (i == index) transform(v) else v })

    private fun VoiceUiState.withModDepth(index: Int, value: Float) =
        copy(voiceModDepths = voiceModDepths.mapIndexed { i, d -> if (i == index) value else d })

    private fun VoiceUiState.withEnvelopeSpeed(index: Int, value: Float) =
        copy(voiceEnvelopeSpeeds = voiceEnvelopeSpeeds.mapIndexed { i, s -> if (i == index) value else s })

    private fun VoiceUiState.withPairSharpness(pairIndex: Int, value: Float) =
        copy(pairSharpness = pairSharpness.mapIndexed { i, s -> if (i == pairIndex) value else s })

    private fun VoiceUiState.withDuoModSource(pairIndex: Int, source: ModSource) =
        copy(duoModSources = duoModSources.mapIndexed { i, s -> if (i == pairIndex) source else s })

    private fun VoiceUiState.withPairEngine(pairIndex: Int, engineOrdinal: Int) =
        copy(pairEngines = pairEngines.mapIndexed { i, e -> if (i == pairIndex) engineOrdinal else e })

    private fun VoiceUiState.withPairHarmonics(pairIndex: Int, value: Float) =
        copy(pairHarmonics = pairHarmonics.mapIndexed { i, h -> if (i == pairIndex) value else h })

    private fun VoiceUiState.withQuadPitch(quadIndex: Int, value: Float) =
        copy(quadGroupPitches = quadGroupPitches.mapIndexed { i, p -> if (i == quadIndex) value else p })

    private fun VoiceUiState.withQuadHold(quadIndex: Int, value: Float) =
        copy(quadGroupHolds = quadGroupHolds.mapIndexed { i, h -> if (i == quadIndex) value else h })

    private fun VoiceUiState.withQuadVolume(quadIndex: Int, value: Float) =
        copy(quadGroupVolumes = quadGroupVolumes.mapIndexed { i, v -> if (i == quadIndex) value else v })

    private fun VoiceUiState.withQuadTriggerSource(quadIndex: Int, sourceIndex: Int) =
        copy(quadTriggerSources = quadTriggerSources.mapIndexed { i, s -> if (i == quadIndex) sourceIndex else s })

    private fun VoiceUiState.withQuadPitchSource(quadIndex: Int, sourceIndex: Int) =
        copy(quadPitchSources = quadPitchSources.mapIndexed { i, s -> if (i == quadIndex) sourceIndex else s })

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
            ControlIds.VIZ_KNOB_2,
            vizKnob2Value,
            ControlEventOrigin.UI
        )
    }

    fun releaseBend() {
        engine.setBend(0f)
        synthController.emitControlChange(ControlIds.VIZ_KNOB_2, 0.5f, ControlEventOrigin.UI)
    }

    // ═══════════════════════════════════════════════════════════
    // PER-STRING BENDER METHODS
    // ═══════════════════════════════════════════════════════════

    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        engine.setStringBend(stringIndex, bendAmount, voiceMix)
        val vizValue = (bendAmount + 1f) / 2f
        if (stringIndex == 0) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_1, vizValue, ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_2, vizValue, ControlEventOrigin.UI)
        }
    }

    fun releaseStringBend(stringIndex: Int): Int {
        if (stringIndex == 0) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_1, 0.5f, ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_2, 0.5f, ControlEventOrigin.UI)
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

@Immutable
data class VoicePanelActions(
    val setMasterVolume: (Float) -> Unit,
    val setVibrato: (Float) -> Unit,
    val setVoiceTune: (Int, Float) -> Unit,
    val setVoiceModDepth: (Int, Float) -> Unit,
    val setDuoModDepth: (Int, Float) -> Unit,
    val setPairSharpness: (Int, Float) -> Unit,
    val setVoiceEnvelopeSpeed: (Int, Float) -> Unit,
    val pulseStart: (Int) -> Unit,
    val pulseEnd: (Int) -> Unit,
    val setHold: (Int, Boolean) -> Unit,
    val setDuoModSource: (Int, ModSource) -> Unit,
    val setQuadPitch: (Int, Float) -> Unit,
    val setQuadHold: (Int, Float) -> Unit,
    val setFmStructure: (Boolean) -> Unit,
    val setTotalFeedback: (Float) -> Unit,
    val setVoiceCoupling: (Float) -> Unit,
    val wobblePulseStart: (Int, Float, Float) -> Unit,
    val wobbleMove: (Int, Float, Float) -> Unit,
    val wobblePulseEnd: (Int) -> Unit,
    val setBend: (Float) -> Unit,
    val releaseBend: () -> Unit,
    val setStringBend: (stringIndex: Int, bendAmount: Float, voiceMix: Float) -> Unit,
    val releaseStringBend: (stringIndex: Int) -> Int,
    val setSlideBar: (yPosition: Float, xPosition: Float) -> Unit,
    val releaseSlideBar: () -> Unit,
    val setBpm: (Double) -> Unit,
    val setQuadTriggerSource: (Int, Int) -> Unit,
    val setQuadPitchSource: (Int, Int) -> Unit,
    val setQuadEnvelopeTriggerMode: (Int, Boolean) -> Unit,
    val setPairEngine: (Int, Int) -> Unit,
    val setPairHarmonics: (Int, Float) -> Unit
) {
    companion object {
        val EMPTY = VoicePanelActions(
            setMasterVolume = {}, setVibrato = {},
            setVoiceTune = {_, _ -> }, setVoiceModDepth = {_, _ -> },
            setDuoModDepth = {_, _ -> }, setPairSharpness = {_, _ -> },
            setVoiceEnvelopeSpeed = {_, _ -> }, pulseStart = {}, pulseEnd = {},
            setHold = {_, _ -> }, setDuoModSource = {_, _ -> },
            setQuadPitch = {_, _ -> }, setQuadHold = {_, _ -> },
            setFmStructure = {}, setTotalFeedback = {},
            setVoiceCoupling = {}, wobblePulseStart = {_, _, _ -> },
            wobbleMove = {_, _, _ -> }, wobblePulseEnd = {},
            setBend = {}, releaseBend = {},
            setStringBend = {_, _, _ -> }, releaseStringBend = { 0 },
            setSlideBar = {_, _ -> }, releaseSlideBar = {},
            setBpm = {}, setQuadTriggerSource = {_, _ -> },
            setQuadPitchSource = {_, _ -> },
            setQuadEnvelopeTriggerMode = {_, _ -> },
            setPairEngine = {_, _ -> },
            setPairHarmonics = {_, _ -> }
        )
    }
}
