package org.balch.orpheus.features.delay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

/** UI state for the Mod Delay panel. */
data class DelayUiState(
    val time1: Float = 0.3f,
    val time2: Float = 0.3f,
    val mod1: Float = 0.0f,
    val mod2: Float = 0.0f,
    val feedback: Float = 0.5f,
    val mix: Float = 0.5f,
    val isLfoSource: Boolean = true,
    val isTriangleWave: Boolean = true
)

@Immutable
data class DelayPanelActions(
    val onTime1Change: (Float) -> Unit,
    val onMod1Change: (Float) -> Unit,
    val onTime2Change: (Float) -> Unit,
    val onMod2Change: (Float) -> Unit,
    val onFeedbackChange: (Float) -> Unit,
    val onMixChange: (Float) -> Unit,
    val onSourceChange: (Boolean) -> Unit,
    val onWaveformChange: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DelayPanelActions(
            onTime1Change = {},
            onMod1Change = {},
            onTime2Change = {},
            onMod2Change = {},
            onFeedbackChange = {},
            onMixChange = {},
            onSourceChange = {},
            onWaveformChange = {}
        )
    }
}

/** User intents for the Delay panel. */
private sealed interface DelayIntent {
    data class Time1(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Time2(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Mod1(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Mod2(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Feedback(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Mix(val value: Float, val fromSequencer: Boolean = false) : DelayIntent
    data class Source(val isLfo: Boolean) : DelayIntent
    data class Waveform(val isTriangle: Boolean) : DelayIntent
    data class Restore(val state: DelayUiState) : DelayIntent
}

typealias DelayFeature = SynthFeature<DelayUiState, DelayPanelActions>

/**
 * ViewModel for the Mod Delay panel.
 *
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 */
@Inject
@ViewModelKey(DelayViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DelayViewModel(
    private val engine: SynthEngine,
    presetLoader: PresetLoader,
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DelayFeature {

    override val actions = DelayPanelActions(
        onTime1Change = ::onTime1Change,
        onMod1Change = ::onMod1Change,
        onTime2Change = ::onTime2Change,
        onMod2Change = ::onMod2Change,
        onFeedbackChange = ::onFeedbackChange,
        onMixChange = ::onMixChange,
        onSourceChange = ::onSourceChange,
        onWaveformChange = ::onWaveformChange
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<DelayIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> DelayIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        DelayIntent.Restore(
            DelayUiState(
                time1 = preset.delayTime1,
                time2 = preset.delayTime2,
                mod1 = preset.delayMod1,
                mod2 = preset.delayMod2,
                feedback = preset.delayFeedback,
                mix = preset.delayMix,
                isLfoSource = preset.delayModSourceIsLfo,
                isTriangleWave = preset.delayLfoWaveformIsTriangle
            )
        )
    }

    // Control changes -> DelayIntent
    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.DELAY_TIME_1 -> DelayIntent.Time1(event.value, fromSequencer)
            ControlIds.DELAY_TIME_2 -> DelayIntent.Time2(event.value, fromSequencer)
            ControlIds.DELAY_MOD_1 -> DelayIntent.Mod1(event.value, fromSequencer)
            ControlIds.DELAY_MOD_2 -> DelayIntent.Mod2(event.value, fromSequencer)
            ControlIds.DELAY_FEEDBACK -> DelayIntent.Feedback(event.value, fromSequencer)
            ControlIds.DELAY_MIX -> DelayIntent.Mix(event.value, fromSequencer)
            ControlIds.DELAY_MOD_SOURCE -> DelayIntent.Source(event.value >= 0.5f)
            ControlIds.DELAY_LFO_WAVEFORM -> DelayIntent.Waveform(event.value >= 0.5f)
            else -> null
        }
    }

    override val stateFlow: StateFlow<DelayUiState> = flow {
        // Emit initial state from engine
        val initial = loadInitialState()
        applyFullState(initial)
        emit(initial)
        
        // Then emit from merged intent sources
        emitAll(
            merge(_userIntents, presetIntents, controlIntents)
                .onEach { intent -> if (intent != null) applyToEngine(intent) }
                .scan(initial) { state, intent -> if (intent != null) reduce(state, intent) else state }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DelayUiState()
    )

    private fun loadInitialState(): DelayUiState {
        return DelayUiState(
            time1 = engine.getDelayTime(0),
            time2 = engine.getDelayTime(1),
            mod1 = engine.getDelayModDepth(0),
            mod2 = engine.getDelayModDepth(1),
            feedback = engine.getDelayFeedback(),
            mix = engine.getDelayMix(),
            isLfoSource = engine.getDelayModSourceIsLfo(0),
            isTriangleWave = engine.getDelayLfoWaveformIsTriangle()
        )
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DelayUiState, intent: DelayIntent): DelayUiState =
        when (intent) {
            is DelayIntent.Time1 -> state.copy(time1 = intent.value)
            is DelayIntent.Time2 -> state.copy(time2 = intent.value)
            is DelayIntent.Mod1 -> state.copy(mod1 = intent.value)
            is DelayIntent.Mod2 -> state.copy(mod2 = intent.value)
            is DelayIntent.Feedback -> state.copy(feedback = intent.value)
            is DelayIntent.Mix -> state.copy(mix = intent.value)
            is DelayIntent.Source -> state.copy(isLfoSource = intent.isLfo)
            is DelayIntent.Waveform -> state.copy(isTriangleWave = intent.isTriangle)
            is DelayIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: DelayIntent) {
        when (intent) {
            // Skip engine calls for SEQUENCER events - engine is driven by audio-rate automation
            is DelayIntent.Time1 -> if (!intent.fromSequencer) engine.setDelayTime(0, intent.value)
            is DelayIntent.Time2 -> if (!intent.fromSequencer) engine.setDelayTime(1, intent.value)
            is DelayIntent.Mod1 -> if (!intent.fromSequencer) engine.setDelayModDepth(0, intent.value)
            is DelayIntent.Mod2 -> if (!intent.fromSequencer) engine.setDelayModDepth(1, intent.value)
            is DelayIntent.Feedback -> if (!intent.fromSequencer) engine.setDelayFeedback(intent.value)
            is DelayIntent.Mix -> if (!intent.fromSequencer) engine.setDelayMix(intent.value)
            is DelayIntent.Source -> {
                engine.setDelayModSource(0, intent.isLfo)
                engine.setDelayModSource(1, intent.isLfo)
            }
            is DelayIntent.Waveform -> engine.setDelayLfoWaveform(intent.isTriangle)
            is DelayIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: DelayUiState) {
        engine.setDelayTime(0, state.time1)
        engine.setDelayTime(1, state.time2)
        engine.setDelayModDepth(0, state.mod1)
        engine.setDelayModDepth(1, state.mod2)
        engine.setDelayFeedback(state.feedback)
        engine.setDelayMix(state.mix)
        engine.setDelayModSource(0, state.isLfoSource)
        engine.setDelayModSource(1, state.isLfoSource)
        engine.setDelayLfoWaveform(state.isTriangleWave)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onTime1Change(value: Float) {
        _userIntents.tryEmit(DelayIntent.Time1(value))
    }

    fun onTime2Change(value: Float) {
        _userIntents.tryEmit(DelayIntent.Time2(value))
    }

    fun onMod1Change(value: Float) {
        _userIntents.tryEmit(DelayIntent.Mod1(value))
    }

    fun onMod2Change(value: Float) {
        _userIntents.tryEmit(DelayIntent.Mod2(value))
    }

    fun onFeedbackChange(value: Float) {
        _userIntents.tryEmit(DelayIntent.Feedback(value))
    }

    fun onMixChange(value: Float) {
        _userIntents.tryEmit(DelayIntent.Mix(value))
    }

    fun onSourceChange(isLfo: Boolean) {
        _userIntents.tryEmit(DelayIntent.Source(isLfo))
    }

    fun onWaveformChange(isTriangle: Boolean) {
        _userIntents.tryEmit(DelayIntent.Waveform(isTriangle))
    }

    fun restoreState(state: DelayUiState) {
        _userIntents.tryEmit(DelayIntent.Restore(state))
    }

    companion object {
        fun previewFeature(state: DelayUiState = DelayUiState()): DelayFeature =
            object : DelayFeature {
                override val stateFlow: StateFlow<DelayUiState> = MutableStateFlow(state)
                override val actions: DelayPanelActions = DelayPanelActions.EMPTY
            }

        @Composable
        fun feature(): DelayFeature =
            synthViewModel<DelayViewModel, DelayFeature>()
    }
}
