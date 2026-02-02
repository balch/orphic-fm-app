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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
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

@Immutable
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
    val setTime1: (Float) -> Unit,
    val setMod1: (Float) -> Unit,
    val setTime2: (Float) -> Unit,
    val setMod2: (Float) -> Unit,
    val setFeedback: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setSource: (Boolean) -> Unit,
    val setWaveform: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DelayPanelActions({}, {}, {}, {}, {}, {}, {}, {})
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
    private val synthController: SynthController,
    private val presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DelayFeature {

    override val actions = DelayPanelActions(
        setTime1 = ::setTime1,
        setMod1 = ::setMod1,
        setTime2 = ::setTime2,
        setMod2 = ::setMod2,
        setFeedback = ::setFeedback,
        setMix = ::setMix,
        setSource = ::setSource,
        setWaveform = ::setWaveform
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
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
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

    override val stateFlow: StateFlow<DelayUiState> =
        merge(_userIntents, presetIntents, controlIntents)
            .scan(DelayUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DelayUiState()
            )

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

    private fun applyToEngine(state: DelayUiState, intent: DelayIntent) {
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

    fun setTime1(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Time1(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_TIME_1, value, ControlEventOrigin.UI)
    }

    fun setTime2(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Time2(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_TIME_2, value, ControlEventOrigin.UI)
    }

    fun setMod1(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Mod1(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_MOD_1, value, ControlEventOrigin.UI)
    }

    fun setMod2(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Mod2(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_MOD_2, value, ControlEventOrigin.UI)
    }

    fun setFeedback(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Feedback(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_FEEDBACK, value, ControlEventOrigin.UI)
    }

    fun setMix(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DelayIntent.Mix(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DELAY_MIX, value, ControlEventOrigin.UI)
    }

    fun setSource(isLfo: Boolean) {
        _userIntents.tryEmit(DelayIntent.Source(isLfo))
        synthController.emitControlChange(ControlIds.DELAY_MOD_SOURCE, if(isLfo) 1.0f else 0.0f, ControlEventOrigin.UI)
    }

    fun setWaveform(isTriangle: Boolean) {
        _userIntents.tryEmit(DelayIntent.Waveform(isTriangle))
        synthController.emitControlChange(ControlIds.DELAY_LFO_WAVEFORM, if(isTriangle) 1.0f else 0.0f, ControlEventOrigin.UI)
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
