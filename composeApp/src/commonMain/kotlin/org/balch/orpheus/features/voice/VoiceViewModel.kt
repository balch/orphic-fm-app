package org.balch.orpheus.features.voice

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.audio.wobble.VoiceWobbleController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel
import kotlin.math.roundToInt

/** UI state for voice management. */
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
    val masterVolume: Float = 1.0f,
    val peakLevel: Float = 0.0f,
    val bendPosition: Float = 0.0f // -1 to +1, current bender position for UI display
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

    // Quad-level intents
    data class QuadPitch(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadHold(val quadIndex: Int, val value: Float) : VoiceIntent
    data class QuadVolume(val quadIndex: Int, val value: Float) : VoiceIntent

    // Global intents
    data class FmStructure(val crossQuad: Boolean) : VoiceIntent
    data class TotalFeedback(val value: Float) : VoiceIntent
    data class Vibrato(val value: Float, val fromSequencer: Boolean = false) : VoiceIntent
    data class VoiceCoupling(val value: Float) : VoiceIntent
    data class MasterVolume(val value: Float) : VoiceIntent
    data class PeakLevel(val value: Float) : VoiceIntent
    data class BendPosition(val value: Float) : VoiceIntent // For UI updates from engine

    // Restore
    data class Restore(val state: VoiceUiState) : VoiceIntent
}


typealias VoicesFeature = SynthFeature<VoiceUiState, VoicePanelActions>
/**
 * ViewModel for voice management.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(VoiceViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class VoiceViewModel(
    private val engine: SynthEngine,
    private val presetLoader: PresetLoader,
    private val synthController: SynthController,
    private val wobbleController: VoiceWobbleController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), VoicesFeature {

    override val actions: VoicePanelActions = VoicePanelActions(
        onMasterVolumeChange = { value ->
            synthController.emitControlChange(ControlIds.MASTER_VOLUME, value, ControlEventOrigin.UI)
        },
        onVibratoChange = { value ->
            synthController.emitControlChange(ControlIds.VIBRATO, value, ControlEventOrigin.UI)
        },
        onVoiceTuneChange = ::onVoiceTuneChange,
        onVoiceModDepthChange = ::onVoiceModDepthChange,
        onDuoModDepthChange = ::onDuoModDepthChange,
        onPairSharpnessChange = ::onPairSharpnessChange,
        onVoiceEnvelopeSpeedChange = ::onVoiceEnvelopeSpeedChange,
        onPulseStart = ::onPulseStart,
        onPulseEnd = ::onPulseEnd,
        onHoldChange = ::onHoldChange,
        onDuoModSourceChange = ::onDuoModSourceChange,
        onQuadPitchChange = ::onQuadPitchChange,
        onQuadHoldChange = ::onQuadHoldChange,
        onFmStructureChange = ::onFmStructureChange,
        onTotalFeedbackChange = ::onTotalFeedbackChange,
        onVoiceCouplingChange = ::onVoiceCouplingChange,
        onWobblePulseStart = ::onWobblePulseStart,
        onWobbleMove = ::onWobbleMove,
        onWobblePulseEnd = ::onWobblePulseEnd,
        onBendChange = ::onBendChange,
        onBendRelease = ::onBendRelease,
        onStringBendChange = ::onStringBendChange,
        onStringBendRelease = ::onStringBendRelease,
        onSlideBarChange = ::onSlideBarChange,
        onSlideBarRelease = ::onSlideBarRelease
    )

    fun onMasterVolumeChange(value: Float) {
        synthController.emitControlChange(ControlIds.MASTER_VOLUME, value, ControlEventOrigin.UI)
    }

    private val intents =
        MutableSharedFlow<VoiceIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    override val stateFlow: StateFlow<VoiceUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(VoiceUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = VoiceUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            stateFlow.value.voiceStates.forEachIndexed { i, v -> engine.setVoiceTune(i, v.tune) }

            // Subscribe to preset changes
            launch {
                presetLoader.presetFlow.collect { preset ->
                    val voiceState =
                        VoiceUiState(
                            voiceStates =
                                stateFlow.value.voiceStates.mapIndexed { index, state ->
                                    state.copy(
                                        tune = preset.voiceTunes.getOrElse(index) { state.tune }
                                    )
                                },
                            voiceModDepths = (preset.voiceModDepths + List(12) { 0f }).take(12),
                            pairSharpness = (preset.pairSharpness + List(6) { 0f }).take(6),
                            voiceEnvelopeSpeeds = (preset.voiceEnvelopeSpeeds + List(12) { 0f }).take(12),
                            duoModSources = (preset.duoModSources + List(6) { ModSource.OFF }).take(6),
                            fmStructureCrossQuad = preset.fmStructureCrossQuad,
                            totalFeedback = preset.totalFeedback,
                            vibrato = preset.vibrato,
                            voiceCoupling = preset.voiceCoupling,
                            quadGroupPitches = (preset.quadGroupPitches + List(3) { 0.5f }).take(3),
                            quadGroupHolds = (preset.quadGroupHolds + List(3) { 0.0f }).take(3),
                            quadGroupVolumes = (preset.quadGroupVolumes + List(3) { 1.0f }).take(3)
                        )
                    intents.tryEmit(VoiceIntent.Restore(voiceState))
                }
            }

            launch {
                synthController.onPulseStart.collect { voiceIndex ->
                    intents.tryEmit(VoiceIntent.PulseStart(voiceIndex))
                }
            }
            launch {
                synthController.onPulseEnd.collect { voiceIndex ->
                    intents.tryEmit(VoiceIntent.PulseEnd(voiceIndex))
                }
            }

            // Subscribe to control changes for voice-related controls
            launch {
                synthController.onControlChange.collect { event ->
                    handleControlChange(event.controlId, event.value, event.origin)
                }
            }

            // Subscribe to peak flow
            launch {
                engine.peakFlow.collect { peak ->
                    intents.tryEmit(VoiceIntent.PeakLevel(peak))
                }
            }
            
            // Subscribe to bend changes (for AI-controlled bending)
            launch {
                synthController.onBendChange.collect { amount ->
                    onBendChange(amount)
                }
            }
            
            // Subscribe to bend flow for UI display updates
            launch {
                engine.bendFlow.collect { bendAmount ->
                    intents.tryEmit(VoiceIntent.BendPosition(bendAmount))
                }
            }
        }
    }

    /**
     * Routes MIDI control changes to the appropriate voice intents.
     */
    private fun handleControlChange(controlId: String, value: Float, origin: ControlEventOrigin = ControlEventOrigin.UI) {
        val fromSequencer = origin == ControlEventOrigin.SEQUENCER
        when (controlId) {
            // Voice tunes
            ControlIds.voiceTune(0) -> intents.tryEmit(VoiceIntent.Tune(0, value))
            ControlIds.voiceTune(1) -> intents.tryEmit(VoiceIntent.Tune(1, value))
            ControlIds.voiceTune(2) -> intents.tryEmit(VoiceIntent.Tune(2, value))
            ControlIds.voiceTune(3) -> intents.tryEmit(VoiceIntent.Tune(3, value))
            ControlIds.voiceTune(4) -> intents.tryEmit(VoiceIntent.Tune(4, value))
            ControlIds.voiceTune(5) -> intents.tryEmit(VoiceIntent.Tune(5, value))
            ControlIds.voiceTune(6) -> intents.tryEmit(VoiceIntent.Tune(6, value))
            ControlIds.voiceTune(7) -> intents.tryEmit(VoiceIntent.Tune(7, value))

            // Voice FM depths (duo-level)
            ControlIds.voiceFmDepth(0), ControlIds.voiceFmDepth(1) -> {
                intents.tryEmit(VoiceIntent.ModDepth(0, value))
                intents.tryEmit(VoiceIntent.ModDepth(1, value))
            }
            ControlIds.voiceFmDepth(2), ControlIds.voiceFmDepth(3) -> {
                intents.tryEmit(VoiceIntent.ModDepth(2, value))
                intents.tryEmit(VoiceIntent.ModDepth(3, value))
            }
            ControlIds.voiceFmDepth(4), ControlIds.voiceFmDepth(5) -> {
                intents.tryEmit(VoiceIntent.ModDepth(4, value))
                intents.tryEmit(VoiceIntent.ModDepth(5, value))
            }
            ControlIds.voiceFmDepth(6), ControlIds.voiceFmDepth(7) -> {
                intents.tryEmit(VoiceIntent.ModDepth(6, value))
                intents.tryEmit(VoiceIntent.ModDepth(7, value))
            }

            // Pair sharpness
            ControlIds.pairSharpness(0) -> intents.tryEmit(VoiceIntent.PairSharpness(0, value))
            ControlIds.pairSharpness(1) -> intents.tryEmit(VoiceIntent.PairSharpness(1, value))
            ControlIds.pairSharpness(2) -> intents.tryEmit(VoiceIntent.PairSharpness(2, value))
            ControlIds.pairSharpness(3) -> intents.tryEmit(VoiceIntent.PairSharpness(3, value))

            // Advanced FM - Vibrato may be sequenced
            ControlIds.VIBRATO -> intents.tryEmit(VoiceIntent.Vibrato(value, fromSequencer))
            ControlIds.VOICE_COUPLING -> intents.tryEmit(VoiceIntent.VoiceCoupling(value))
            ControlIds.TOTAL_FEEDBACK -> intents.tryEmit(VoiceIntent.TotalFeedback(value))
            ControlIds.MASTER_VOLUME -> intents.tryEmit(VoiceIntent.MasterVolume(value))

            // Quad controls
            ControlIds.quadPitch(0) -> intents.tryEmit(VoiceIntent.QuadPitch(0, value))
            ControlIds.quadPitch(1) -> intents.tryEmit(VoiceIntent.QuadPitch(1, value))
            ControlIds.quadHold(0) -> intents.tryEmit(VoiceIntent.QuadHold(0, value))
            ControlIds.quadHold(1) -> intents.tryEmit(VoiceIntent.QuadHold(1, value))
            ControlIds.quadVolume(0) -> intents.tryEmit(VoiceIntent.QuadVolume(0, value))
            ControlIds.quadVolume(1) -> intents.tryEmit(VoiceIntent.QuadVolume(1, value))
            ControlIds.quadVolume(2) -> intents.tryEmit(VoiceIntent.QuadVolume(2, value))
            
            ControlIds.FM_STRUCTURE -> intents.tryEmit(VoiceIntent.FmStructure(value > 0.5f))

            else -> handleDynamicControlId(controlId, value)
        }
    }

    private fun handleDynamicControlId(controlId: String, value: Float) {
        when {
            controlId.startsWith("voice_") && controlId.endsWith("_tune") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_tune").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.Tune(index, value))
            }

            controlId.startsWith("voice_") && controlId.endsWith("_fm_depth") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_fm_depth").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.ModDepth(index, value))
            }

            controlId.startsWith("voice_") && controlId.endsWith("_env_speed") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_env_speed").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.EnvelopeSpeed(index, value))
            }

            controlId.startsWith("voice_") && controlId.endsWith("_hold") -> {
                val index = controlId.removePrefix("voice_").removeSuffix("_hold").toIntOrNull()
                // Treat as holding if value > 0.1f (allows continuous control from REPL)
                if (index != null) intents.tryEmit(VoiceIntent.Hold(index, value > 0.1f))
            }

            controlId.startsWith("pair_") && controlId.endsWith("_sharpness") -> {
                val index = controlId.removePrefix("pair_").removeSuffix("_sharpness").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.PairSharpness(index, value))
            }

            controlId.startsWith("pair_") && controlId.endsWith("_mod_source") -> {
                val index = controlId.removePrefix("pair_").removeSuffix("_mod_source").toIntOrNull()
                if (index != null) {
                    val sources = ModSource.values()
                    val srcIndex = (value * (sources.size - 1)).roundToInt()
                    intents.tryEmit(VoiceIntent.DuoModSource(index, sources[srcIndex]))
                }
            }
            
            // Quad-level hold controls from REPL
            controlId.startsWith("quad_") && controlId.endsWith("_hold") -> {
                val index = controlId.removePrefix("quad_").removeSuffix("_hold").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.QuadHold(index, value))
            }
            
            // Quad-level pitch controls from REPL
            controlId.startsWith("quad_") && controlId.endsWith("_pitch") -> {
                val index = controlId.removePrefix("quad_").removeSuffix("_pitch").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.QuadPitch(index, value))
            }
            
            // Quad-level volume controls from AI/REPL
            controlId.startsWith("quad_") && controlId.endsWith("_volume") -> {
                val index = controlId.removePrefix("quad_").removeSuffix("_volume").toIntOrNull()
                if (index != null) intents.tryEmit(VoiceIntent.QuadVolume(index, value))
            }

            // Fallback for direct param names that might come from AI
            controlId == "master_volume" -> intents.tryEmit(VoiceIntent.MasterVolume(value))
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

            is VoiceIntent.QuadPitch ->
                state.withQuadPitch(intent.quadIndex, intent.value)

            is VoiceIntent.QuadHold ->
                state.withQuadHold(intent.quadIndex, intent.value)

            is VoiceIntent.QuadVolume ->
                state.withQuadVolume(intent.quadIndex, intent.value)

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

            is VoiceIntent.Restore -> intent.state
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

    private fun VoiceUiState.withQuadPitch(quadIndex: Int, value: Float) =
        copy(quadGroupPitches = quadGroupPitches.mapIndexed { i, p -> if (i == quadIndex) value else p })

    private fun VoiceUiState.withQuadHold(quadIndex: Int, value: Float) =
        copy(quadGroupHolds = quadGroupHolds.mapIndexed { i, h -> if (i == quadIndex) value else h })

    private fun VoiceUiState.withQuadVolume(quadIndex: Int, value: Float) =
        copy(quadGroupVolumes = quadGroupVolumes.mapIndexed { i, v -> if (i == quadIndex) value else v })


    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.Tune -> engine.setVoiceTune(intent.index, intent.value)
            is VoiceIntent.ModDepth ->
                engine.setVoiceFmDepth(intent.index, intent.value)

            is VoiceIntent.EnvelopeSpeed ->
                engine.setVoiceEnvelopeSpeed(intent.index, intent.value)

            is VoiceIntent.PulseStart -> engine.setVoiceGate(intent.index, true)
            is VoiceIntent.PulseEnd -> {
                val voice = stateFlow.value.voiceStates[intent.index]
                // Always close the gate to trigger envelope release
                engine.setVoiceGate(intent.index, false)
                // If holding, the hold level provides the floor that VCA won't go below
                // (hold level was already set when Hold was activated)
            }

            is VoiceIntent.Hold -> {
                if (intent.holding) {
                    // Set hold level based on envelope speed (same as QuadHold behavior)
                    // Speed 0 (fast) → 0.5 hold level, Speed 1 (slow) → 1.0 hold level
                    val envSpeed = stateFlow.value.voiceEnvelopeSpeeds[intent.index]
                    val holdLevel = 0.5f + (envSpeed * 0.5f)
                    engine.setVoiceHold(intent.index, holdLevel)
                } else {
                    engine.setVoiceHold(intent.index, 0f)
                    val voice = stateFlow.value.voiceStates[intent.index]
                    if (!voice.pulse) engine.setVoiceGate(intent.index, false)
                }
            }

            is VoiceIntent.PairSharpness ->
                engine.setPairSharpness(intent.pairIndex, intent.value)

            is VoiceIntent.DuoModSource ->
                engine.setDuoModSource(intent.pairIndex, intent.source)

            is VoiceIntent.QuadPitch ->
                engine.setQuadPitch(intent.quadIndex, intent.value)

            is VoiceIntent.QuadHold ->
                engine.setQuadHold(intent.quadIndex, intent.value)

            is VoiceIntent.QuadVolume ->
                engine.setQuadVolume(intent.quadIndex, intent.value)

            is VoiceIntent.FmStructure -> {
                engine.setFmStructure(intent.crossQuad)
                stateFlow.value.duoModSources.forEachIndexed { index, source ->
                    if (source == ModSource.VOICE_FM)
                        engine.setDuoModSource(index, source)
                }
            }

            is VoiceIntent.TotalFeedback -> engine.setTotalFeedback(intent.value)
            // Skip engine call for SEQUENCER events - engine is driven by audio-rate automation
            is VoiceIntent.Vibrato -> if (!intent.fromSequencer) engine.setVibrato(intent.value)
            is VoiceIntent.VoiceCoupling -> engine.setVoiceCoupling(intent.value)
            is VoiceIntent.MasterVolume -> engine.setMasterVolume(intent.value)
            is VoiceIntent.PeakLevel -> { /* No side effect, strictly monitoring */ }
            is VoiceIntent.BendPosition -> { /* No side effect, UI display only - engine already updated via bendFlow */ }
            is VoiceIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: VoiceUiState) {
        state.voiceStates.forEachIndexed { i, v -> engine.setVoiceTune(i, v.tune) }
        state.voiceModDepths.forEachIndexed { i, d -> engine.setVoiceFmDepth(i, d) }
        state.pairSharpness.forEachIndexed { i, s -> engine.setPairSharpness(i, s) }
        state.voiceEnvelopeSpeeds.forEachIndexed { i, s ->
            engine.setVoiceEnvelopeSpeed(i, s)
        }
        state.quadGroupPitches.forEachIndexed { i, p -> engine.setQuadPitch(i, p) }
        state.quadGroupHolds.forEachIndexed { i, h -> engine.setQuadHold(i, h) }
        state.quadGroupVolumes.forEachIndexed { i, v -> engine.setQuadVolume(i, v) }
        
        // IMPORTANT: Set FM structure BEFORE duoModSources!
        // VOICE_FM routing in setDuoModSource depends on the fmStructureCrossQuad flag
        engine.setFmStructure(state.fmStructureCrossQuad)
        state.duoModSources.forEachIndexed { i, s -> engine.setDuoModSource(i, s) }
        
        engine.setTotalFeedback(state.totalFeedback)
        engine.setVibrato(state.vibrato)
        engine.setVoiceCoupling(state.voiceCoupling)
        engine.setMasterVolume(state.masterVolume)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onVoiceTuneChange(index: Int, value: Float) {
        synthController.emitControlChange(ControlIds.voiceTune(index), value, ControlEventOrigin.UI)
    }

    fun onVoiceModDepthChange(index: Int, value: Float) {
        val duoIndex = index / 2
        synthController.emitControlChange(ControlIds.voiceFmDepth(index), value, ControlEventOrigin.UI)
        // Also update the pair?
        // Actually onDuoModDepthChange updates both. 
        // If we want individual control via UI, we emit individually.
        // But UI usually calls onDuoModDepthChange.
    }

    fun onDuoModDepthChange(duoIndex: Int, value: Float) {
        // Emit for both voices in the duo
        synthController.emitControlChange(ControlIds.voiceFmDepth(duoIndex * 2), value, ControlEventOrigin.UI)
        synthController.emitControlChange(ControlIds.voiceFmDepth(duoIndex * 2 + 1), value, ControlEventOrigin.UI)
    }

    fun onPairSharpnessChange(pairIndex: Int, value: Float) {
        synthController.emitControlChange(ControlIds.pairSharpness(pairIndex), value, ControlEventOrigin.UI)
    }

    fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) {
        synthController.emitControlChange(ControlIds.voiceEnvelopeSpeed(index), value, ControlEventOrigin.UI)
    }

    fun onPulseStart(index: Int) {
        // Reset per-string benders to ensure clean state when pulsing from voice pads
        // This handles the case when user switches from strings panel to voice pads
        engine.resetStringBenders()
        synthController.emitPulseStart(index)
    }


    fun onPulseEnd(index: Int) {
        synthController.emitPulseEnd(index)
    }

    fun onHoldChange(index: Int, holding: Boolean) {
        val value = if (holding) 1.0f else 0.0f
        synthController.emitControlChange(ControlIds.voiceHold(index), value, ControlEventOrigin.UI)
    }

    fun onDuoModSourceChange(index: Int, source: ModSource) {
        val sources = ModSource.values()
        // Map ordinal to 0..1 range approximately to match handleDynamicControlId logic
        // But handleDynamicControlId does: (value * (sources.size - 1)).roundToInt()
        // So value should be index / (size - 1)
        val value = if (sources.size > 1) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.duoModSource(index), value, ControlEventOrigin.UI)
    }

    fun onQuadPitchChange(index: Int, value: Float) {
        synthController.emitControlChange(ControlIds.quadPitch(index), value, ControlEventOrigin.UI)
    }

    fun onQuadHoldChange(index: Int, value: Float) {
        synthController.emitControlChange(ControlIds.quadHold(index), value, ControlEventOrigin.UI)
    }

    fun onFmStructureChange(crossQuad: Boolean) {
        val value = if (crossQuad) 1.0f else 0.0f
        synthController.emitControlChange(ControlIds.FM_STRUCTURE, value, ControlEventOrigin.UI)
    }

    fun onTotalFeedbackChange(value: Float) {
        synthController.emitControlChange(ControlIds.TOTAL_FEEDBACK, value, ControlEventOrigin.UI)
    }

    fun onVoiceCouplingChange(value: Float) {
        synthController.emitControlChange(ControlIds.VOICE_COUPLING, value, ControlEventOrigin.UI)
    }

    fun restoreState(state: VoiceUiState) {
        // Restore assumes full state reset, bypass controller to avoid event storm
        intents.tryEmit(VoiceIntent.Restore(state))
    }
    
    // ═══════════════════════════════════════════════════════════
    // WOBBLE METHODS
    // ═══════════════════════════════════════════════════════════
    
    /**
     * Called when a pulse starts with initial pointer position for wobble tracking.
     */
    fun onWobblePulseStart(index: Int, x: Float, y: Float) {
        wobbleController.onPulseStart(index, x, y)
        // Reset wobble multiplier to 1.0 at start
        engine.setVoiceWobble(index, 0f, wobbleController.config.value.range)
    }
    
    /**
     * Called continuously as finger moves during pulse.
     * Updates the wobble modulation in real-time.
     */
    fun onWobbleMove(index: Int, x: Float, y: Float) {
        val wobbleOffset = wobbleController.onPointerMove(index, x, y)
        val range = wobbleController.config.value.range
        engine.setVoiceWobble(index, wobbleOffset, range)
    }
    
    /**
     * Called when pulse ends.
     * Captures final wobble state and resets modulation.
     */
    fun onWobblePulseEnd(index: Int) {
        wobbleController.onPulseEnd(index)
        // Reset to unity gain on release
        engine.setVoiceWobble(index, 0f, 0f)
    }
    
    // ═══════════════════════════════════════════════════════════
    // BENDER METHODS
    // ═══════════════════════════════════════════════════════════
    
    /**
     * Called continuously as the bender slider is dragged.
     * @param amount Bend amount from -1 (down) to +1 (up), 0 = center
     */
    fun onBendChange(amount: Float) {
        // Reset per-string benders to ensure clean state when using global bender
        // This handles the case when user switches from strings panel to voice pads
        engine.resetStringBenders()
        
        engine.setBend(amount)
        
        // Also affect the current visualizer's knob2
        // Map bend amount (-1 to +1) to knob range (0 to 1)
        // Center (0) = 0.5, full up (+1) = 1.0, full down (-1) = 0.0
        val vizKnob2Value = (amount + 1f) / 2f
        synthController.emitControlChange(
            ControlIds.VIZ_KNOB_2, 
            vizKnob2Value, 
            ControlEventOrigin.UI
        )
    }

    
    /**
     * Called when the bender slider is released.
     * The UI handles the spring animation; this resets the engine state.
     */
    fun onBendRelease() {
        engine.setBend(0f)
        // Reset viz knob2 to center
        synthController.emitControlChange(ControlIds.VIZ_KNOB_2, 0.5f, ControlEventOrigin.UI)
    }
    
    // ═══════════════════════════════════════════════════════════
    // PER-STRING BENDER METHODS
    // ═══════════════════════════════════════════════════════════
    
    /**
     * Called when a string is bent (during drag).
     * @param stringIndex 0-3
     * @param bendAmount Horizontal deflection -1 to +1
     * @param voiceMix Vertical position 0 to 1 (0=top/voice A, 0.5=center, 1=bottom/voice B)
     */
    fun onStringBendChange(stringIndex: Int, bendAmount: Float, voiceMix: Float) {
        engine.setStringBend(stringIndex, bendAmount, voiceMix)
        
        // Map string bends to Viz Knobs
        // Purple (0) -> Knob 1
        // Green (3) -> Knob 2
        // Map -1..1 to 0..1
        val vizValue = (bendAmount + 1f) / 2f
        
        if (stringIndex == 0) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_1, vizValue, ControlEventOrigin.UI)
        } else if (stringIndex == 3) {
            synthController.emitControlChange(ControlIds.VIZ_KNOB_2, vizValue, ControlEventOrigin.UI)
        }
    }
    
    /**
     * Called when a string is released. Returns spring duration for UI animation.
     * @param stringIndex 0-3
     * @return Spring duration in milliseconds
     */
    fun onStringBendRelease(stringIndex: Int): Int {
        // Reset viz knobs if applicable
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
    
    /**
     * Called when the slide bar is moved.
     * @param yPosition 0 to 1 (0=top, 1=bottom) - down = higher pitch
     * @param xPosition 0 to 1 (horizontal) - wiggling creates vibrato
     */
    fun onSlideBarChange(yPosition: Float, xPosition: Float) {
        engine.setSlideBar(yPosition, xPosition)
    }
    
    /**
     * Called when the slide bar is released.
     */
    fun onSlideBarRelease() {
        engine.releaseSlideBar()
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

data class VoicePanelActions(
    val onMasterVolumeChange: (Float) -> Unit,
    val onVibratoChange: (Float) -> Unit,
    val onVoiceTuneChange: (Int, Float) -> Unit,
    val onVoiceModDepthChange: (Int, Float) -> Unit,
    val onDuoModDepthChange: (Int, Float) -> Unit,
    val onPairSharpnessChange: (Int, Float) -> Unit,
    val onVoiceEnvelopeSpeedChange: (Int, Float) -> Unit,
    val onPulseStart: (Int) -> Unit,
    val onPulseEnd: (Int) -> Unit,
    val onHoldChange: (Int, Boolean) -> Unit,
    val onDuoModSourceChange: (Int, ModSource) -> Unit,
    val onQuadPitchChange: (Int, Float) -> Unit,
    val onQuadHoldChange: (Int, Float) -> Unit,
    val onFmStructureChange: (Boolean) -> Unit,
    val onTotalFeedbackChange: (Float) -> Unit,
    val onVoiceCouplingChange: (Float) -> Unit,
    val onWobblePulseStart: (Int, Float, Float) -> Unit,
    val onWobbleMove: (Int, Float, Float) -> Unit,
    val onWobblePulseEnd: (Int) -> Unit,
    val onBendChange: (Float) -> Unit,
    val onBendRelease: () -> Unit,
    val onStringBendChange: (stringIndex: Int, bendAmount: Float, voiceMix: Float) -> Unit,
    val onStringBendRelease: (stringIndex: Int) -> Int,
    val onSlideBarChange: (yPosition: Float, xPosition: Float) -> Unit,
    val onSlideBarRelease: () -> Unit
) {
    companion object {
        val EMPTY = VoicePanelActions(
            onMasterVolumeChange = {}, onVibratoChange = {}, 
            onVoiceTuneChange = {_, _ -> }, onVoiceModDepthChange = {_, _ -> }, 
            onDuoModDepthChange = {_, _ -> }, onPairSharpnessChange = {_, _ -> },
            onVoiceEnvelopeSpeedChange = {_, _ -> }, onPulseStart = {}, onPulseEnd = {},
            onHoldChange = {_, _ -> }, onDuoModSourceChange = {_, _ -> },
            onQuadPitchChange = {_, _ -> }, onQuadHoldChange = {_, _ -> },
            onFmStructureChange = {}, onTotalFeedbackChange = {},
            onVoiceCouplingChange = {}, onWobblePulseStart = {_, _, _ -> },
            onWobbleMove = {_, _, _ -> }, onWobblePulseEnd = {},
            onBendChange = {}, onBendRelease = {},
            onStringBendChange = {_, _, _ -> }, onStringBendRelease = { 0 },
            onSlideBarChange = {_, _ -> }, onSlideBarRelease = {}
        )
    }
}

// Extension function for List padding
private fun <T> List<T>.padEnd(size: Int, element: T): List<T> {
    return if (this.size >= size) this.take(size) else this + List(size - this.size) { element }
}
