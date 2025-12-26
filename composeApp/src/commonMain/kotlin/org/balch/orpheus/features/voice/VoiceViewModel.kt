package org.balch.orpheus.features.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.midi.MidiRouter
import org.balch.orpheus.core.presets.PresetLoader
import kotlin.math.roundToInt

/** UI state for voice management. */
data class VoiceUiState(
    val voiceStates: List<VoiceState> =
        List(8) { index -> VoiceState(index = index, tune = DEFAULT_TUNINGS[index]) },
    val voiceModDepths: List<Float> = List(8) { 0.0f },
    val pairSharpness: List<Float> = List(4) { 0.0f },
    val voiceEnvelopeSpeeds: List<Float> = List(8) { 0.0f },
    val duoModSources: List<ModSource> = List(4) { ModSource.OFF },
    val quadGroupPitches: List<Float> = List(2) { 0.5f },
    val quadGroupHolds: List<Float> = List(2) { 0.0f },
    val fmStructureCrossQuad: Boolean = false,
    val totalFeedback: Float = 0.0f,
    val vibrato: Float = 0.0f,
    val voiceCoupling: Float = 0.0f,
    val masterVolume: Float = 1.0f,
    val peakLevel: Float = 0.0f
) {
    companion object {
        val DEFAULT_TUNINGS = listOf(0.20f, 0.27f, 0.34f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f)
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

    // Global intents
    data class FmStructure(val crossQuad: Boolean) : VoiceIntent
    data class TotalFeedback(val value: Float) : VoiceIntent
    data class Vibrato(val value: Float) : VoiceIntent
    data class VoiceCoupling(val value: Float) : VoiceIntent
    data class MasterVolume(val value: Float) : VoiceIntent
    data class PeakLevel(val value: Float) : VoiceIntent

    // Restore
    data class Restore(val state: VoiceUiState) : VoiceIntent
}

/**
 * ViewModel for voice management.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(VoiceViewModel::class)
@ContributesIntoMap(AppScope::class)
class VoiceViewModel(
    private val engine: SynthEngine,
    private val presetLoader: PresetLoader,
    private val midiRouter: Lazy<MidiRouter>,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<VoiceIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<VoiceUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(VoiceUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = VoiceUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            uiState.value.voiceStates.forEachIndexed { i, v -> engine.setVoiceTune(i, v.tune) }

            // Subscribe to preset changes
            launch {
                presetLoader.presetFlow.collect { preset ->
                    val voiceState =
                        VoiceUiState(
                            voiceStates =
                                uiState.value.voiceStates.mapIndexed { index, state ->
                                    state.copy(
                                        tune = preset.voiceTunes.getOrElse(index) { state.tune }
                                    )
                                },
                            voiceModDepths = preset.voiceModDepths.take(8).padEnd(8, 0f),
                            pairSharpness = preset.pairSharpness.take(4).padEnd(4, 0f),
                            voiceEnvelopeSpeeds = preset.voiceEnvelopeSpeeds.take(8).padEnd(8, 0f),
                            duoModSources =
                                preset.duoModSources
                                    .mapNotNull {
                                        try {
                                            ModSource.valueOf(it)
                                        } catch (e: Exception) {
                                            ModSource.OFF
                                        }
                                    }
                                    .take(4)
                                    .padEnd(4, ModSource.OFF),
                            fmStructureCrossQuad = preset.fmStructureCrossQuad,
                            totalFeedback = preset.totalFeedback
                        )
                    intents.tryEmit(VoiceIntent.Restore(voiceState))
                }
            }

            // Subscribe to MIDI pulse events
            launch {
                midiRouter.value.onPulseStart.collect { voiceIndex ->
                    intents.tryEmit(VoiceIntent.PulseStart(voiceIndex))
                }
            }
            launch {
                midiRouter.value.onPulseEnd.collect { voiceIndex ->
                    intents.tryEmit(VoiceIntent.PulseEnd(voiceIndex))
                }
            }

            // Subscribe to MIDI control changes for voice-related controls
            launch {
                midiRouter.value.onControlChange.collect { event ->
                    handleMidiControlChange(event.controlId, event.value)
                }
            }

            // Subscribe to peak flow
            launch {
                engine.peakFlow.collect { peak ->
                    intents.tryEmit(VoiceIntent.PeakLevel(peak))
                }
            }
        }
    }

    /**
     * Routes MIDI control changes to the appropriate voice intents.
     */
    private fun handleMidiControlChange(controlId: String, value: Float) {
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
            ControlIds.voiceFmDepth(0), ControlIds.voiceFmDepth(1) ->
                onDuoModDepthChange(0, value)
            ControlIds.voiceFmDepth(2), ControlIds.voiceFmDepth(3) ->
                onDuoModDepthChange(1, value)
            ControlIds.voiceFmDepth(4), ControlIds.voiceFmDepth(5) ->
                onDuoModDepthChange(2, value)
            ControlIds.voiceFmDepth(6), ControlIds.voiceFmDepth(7) ->
                onDuoModDepthChange(3, value)

            // Pair sharpness
            ControlIds.pairSharpness(0) -> intents.tryEmit(VoiceIntent.PairSharpness(0, value))
            ControlIds.pairSharpness(1) -> intents.tryEmit(VoiceIntent.PairSharpness(1, value))
            ControlIds.pairSharpness(2) -> intents.tryEmit(VoiceIntent.PairSharpness(2, value))
            ControlIds.pairSharpness(3) -> intents.tryEmit(VoiceIntent.PairSharpness(3, value))

            // Advanced FM
            ControlIds.VIBRATO -> intents.tryEmit(VoiceIntent.Vibrato(value))
            ControlIds.VOICE_COUPLING -> intents.tryEmit(VoiceIntent.VoiceCoupling(value))
            ControlIds.TOTAL_FEEDBACK -> intents.tryEmit(VoiceIntent.TotalFeedback(value))

            // Quad controls
            ControlIds.quadPitch(0) -> intents.tryEmit(VoiceIntent.QuadPitch(0, value))
            ControlIds.quadPitch(1) -> intents.tryEmit(VoiceIntent.QuadPitch(1, value))
            ControlIds.quadHold(0) -> intents.tryEmit(VoiceIntent.QuadHold(0, value))
            ControlIds.quadHold(1) -> intents.tryEmit(VoiceIntent.QuadHold(1, value))

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
                if (index != null) intents.tryEmit(VoiceIntent.Hold(index, value >= 0.5f))
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
                val voice = uiState.value.voiceStates[intent.index]
                // Always close the gate to trigger envelope release
                engine.setVoiceGate(intent.index, false)
                // If holding, the hold level provides the floor that VCA won't go below
                // (hold level was already set when Hold was activated)
            }

            is VoiceIntent.Hold -> {
                if (intent.holding) {
                    // Set hold level based on envelope speed (same as QuadHold behavior)
                    // Speed 0 (fast) → 0.5 hold level, Speed 1 (slow) → 1.0 hold level
                    val envSpeed = uiState.value.voiceEnvelopeSpeeds[intent.index]
                    val holdLevel = 0.5f + (envSpeed * 0.5f)
                    engine.setVoiceHold(intent.index, holdLevel)
                } else {
                    engine.setVoiceHold(intent.index, 0f)
                    val voice = uiState.value.voiceStates[intent.index]
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

            is VoiceIntent.FmStructure -> {
                engine.setFmStructure(intent.crossQuad)
                uiState.value.duoModSources.forEachIndexed { index, source ->
                    if (source == ModSource.VOICE_FM)
                        engine.setDuoModSource(index, source)
                }
            }

            is VoiceIntent.TotalFeedback -> engine.setTotalFeedback(intent.value)
            is VoiceIntent.Vibrato -> engine.setVibrato(intent.value)
            is VoiceIntent.VoiceCoupling -> engine.setVoiceCoupling(intent.value)
            is VoiceIntent.MasterVolume -> engine.setMasterVolume(intent.value)
            is VoiceIntent.PeakLevel -> { /* No side effect, strictly monitoring */ }
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
        state.duoModSources.forEachIndexed { i, s -> engine.setDuoModSource(i, s) }
        state.quadGroupPitches.forEachIndexed { i, p -> engine.setQuadPitch(i, p) }
        state.quadGroupHolds.forEachIndexed { i, h -> engine.setQuadHold(i, h) }
        engine.setFmStructure(state.fmStructureCrossQuad)
        engine.setTotalFeedback(state.totalFeedback)
        engine.setVibrato(state.vibrato)
        engine.setVoiceCoupling(state.voiceCoupling)
        engine.setMasterVolume(state.masterVolume)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onVoiceTuneChange(index: Int, value: Float) {
        intents.tryEmit(VoiceIntent.Tune(index, value))
    }

    fun onVoiceModDepthChange(index: Int, value: Float) {
        intents.tryEmit(VoiceIntent.ModDepth(index, value))
    }

    fun onDuoModDepthChange(duoIndex: Int, value: Float) {
        intents.tryEmit(VoiceIntent.ModDepth(duoIndex * 2, value))
        intents.tryEmit(VoiceIntent.ModDepth(duoIndex * 2 + 1, value))
    }

    fun onPairSharpnessChange(pairIndex: Int, value: Float) {
        intents.tryEmit(VoiceIntent.PairSharpness(pairIndex, value))
    }

    fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) {
        intents.tryEmit(VoiceIntent.EnvelopeSpeed(index, value))
    }

    fun onPulseStart(index: Int) {
        intents.tryEmit(VoiceIntent.PulseStart(index))
    }

    fun onPulseEnd(index: Int) {
        intents.tryEmit(VoiceIntent.PulseEnd(index))
    }

    fun onHoldChange(index: Int, holding: Boolean) {
        intents.tryEmit(VoiceIntent.Hold(index, holding))
    }

    fun onDuoModSourceChange(index: Int, source: ModSource) {
        intents.tryEmit(VoiceIntent.DuoModSource(index, source))
    }

    fun onQuadPitchChange(index: Int, value: Float) {
        intents.tryEmit(VoiceIntent.QuadPitch(index, value))
    }

    fun onQuadHoldChange(index: Int, value: Float) {
        intents.tryEmit(VoiceIntent.QuadHold(index, value))
    }

    fun onFmStructureChange(crossQuad: Boolean) {
        intents.tryEmit(VoiceIntent.FmStructure(crossQuad))
    }

    fun onTotalFeedbackChange(value: Float) {
        intents.tryEmit(VoiceIntent.TotalFeedback(value))
    }

    fun onVibratoChange(value: Float) {
        intents.tryEmit(VoiceIntent.Vibrato(value))
    }

    fun onVoiceCouplingChange(value: Float) {
        intents.tryEmit(VoiceIntent.VoiceCoupling(value))
    }

    fun onMasterVolumeChange(value: Float) {
        intents.tryEmit(VoiceIntent.MasterVolume(value))
    }

    fun restoreState(state: VoiceUiState) {
        intents.tryEmit(VoiceIntent.Restore(state))
    }
}

// Extension function for List padding
private fun <T> List<T>.padEnd(size: Int, element: T): List<T> {
    return if (this.size >= size) this.take(size) else this + List(size - this.size) { element }
}
