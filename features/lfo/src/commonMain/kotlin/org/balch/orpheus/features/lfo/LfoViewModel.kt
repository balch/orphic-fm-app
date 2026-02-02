package org.balch.orpheus.features.lfo

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
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

@Immutable
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false
)

@Immutable
data class LfoPanelActions(
    val setLfoA: (Float) -> Unit,
    val setLfoB: (Float) -> Unit,
    val setMode: (HyperLfoMode) -> Unit,
    val setLink: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = LfoPanelActions({}, {}, {}, {})
    }
}

/** User intents for the LFO panel. */
private sealed interface LfoIntent {
    data class LfoA(val value: Float, val fromSequencer: Boolean = false) : LfoIntent
    data class LfoB(val value: Float, val fromSequencer: Boolean = false) : LfoIntent
    data class Mode(val mode: HyperLfoMode) : LfoIntent
    data class Link(val enabled: Boolean) : LfoIntent
    data class Restore(val state: LfoUiState) : LfoIntent
}

typealias LfoFeature = SynthFeature<LfoUiState, LfoPanelActions>

/**
 * ViewModel for the Hyper LFO panel.
 *
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 */
@Inject
@ViewModelKey(LfoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LfoViewModel(
    private val engine: SynthEngine,
    private val presetLoader: PresetLoader,
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), LfoFeature {

    override val actions = LfoPanelActions(
        setLfoA = ::setLfoA,
        setLfoB = ::setLfoB,
        setMode = ::setMode,
        setLink = ::setLink
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<LfoIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> LfoIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        LfoIntent.Restore(
            LfoUiState(
                lfoA = preset.hyperLfoA,
                lfoB = preset.hyperLfoB,
                mode = preset.hyperLfoMode,
                linkEnabled = preset.hyperLfoLink
            )
        )
    }

    // Control changes -> LfoIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.HYPER_LFO_A -> LfoIntent.LfoA(event.value, fromSequencer)
            ControlIds.HYPER_LFO_B -> LfoIntent.LfoB(event.value, fromSequencer)
            ControlIds.HYPER_LFO_MODE -> {
                val modes = HyperLfoMode.entries.toTypedArray()
                val index = (event.value * (modes.size - 1)).toInt().coerceIn(0, modes.size - 1)
                LfoIntent.Mode(modes[index])
            }
            ControlIds.HYPER_LFO_LINK -> LfoIntent.Link(event.value >= 0.5f)
            else -> null
        }
    }

    override val stateFlow: StateFlow<LfoUiState> =
        merge(_userIntents, presetIntents, controlIntents)
        .scan(LfoUiState()) { state, intent ->
            val newState = reduce(state, intent)
            applyToEngine(newState, intent)
            newState
        }
        .flowOn(dispatcherProvider.io)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LfoUiState()
        )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: LfoUiState, intent: LfoIntent): LfoUiState =
        when (intent) {
            is LfoIntent.LfoA -> state.copy(lfoA = intent.value)
            is LfoIntent.LfoB -> state.copy(lfoB = intent.value)
            is LfoIntent.Mode -> state.copy(mode = intent.mode)
            is LfoIntent.Link -> state.copy(linkEnabled = intent.enabled)
            is LfoIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: LfoUiState, intent: LfoIntent) {
        when (intent) {
            // Skip engine calls for SEQUENCER events - engine is driven by audio-rate automation
            is LfoIntent.LfoA -> if (!intent.fromSequencer) engine.setHyperLfoFreq(0, intent.value)
            is LfoIntent.LfoB -> if (!intent.fromSequencer) engine.setHyperLfoFreq(1, intent.value)
            is LfoIntent.Mode -> engine.setHyperLfoMode(intent.mode.ordinal)
            is LfoIntent.Link -> engine.setHyperLfoLink(intent.enabled)
            is LfoIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: LfoUiState) {
        engine.setHyperLfoFreq(0, state.lfoA)
        engine.setHyperLfoFreq(1, state.lfoB)
        engine.setHyperLfoMode(state.mode.ordinal)
        engine.setHyperLfoLink(state.linkEnabled)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setLfoA(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(LfoIntent.LfoA(value, fromSequencer))
        synthController.emitControlChange(ControlIds.HYPER_LFO_A, value, ControlEventOrigin.UI)
    }

    fun setLfoB(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(LfoIntent.LfoB(value, fromSequencer))
        synthController.emitControlChange(ControlIds.HYPER_LFO_B, value, ControlEventOrigin.UI)
    }

    fun setMode(mode: HyperLfoMode) {
        _userIntents.tryEmit(LfoIntent.Mode(mode))
        val modes = HyperLfoMode.entries
        val value = if (modes.isNotEmpty()) mode.ordinal.toFloat() / (modes.size - 1) else 0f
        synthController.emitControlChange(ControlIds.HYPER_LFO_MODE, value, ControlEventOrigin.UI)
    }

    fun setLink(enabled: Boolean) {
        _userIntents.tryEmit(LfoIntent.Link(enabled))
        synthController.emitControlChange(ControlIds.HYPER_LFO_LINK, if (enabled) 1f else 0f, ControlEventOrigin.UI)
    }

    fun restoreState(state: LfoUiState) {
        _userIntents.tryEmit(LfoIntent.Restore(state))
    }

    companion object {
        fun previewFeature(state: LfoUiState = LfoUiState()): LfoFeature =
            object : LfoFeature {
                override val stateFlow: StateFlow<LfoUiState> = MutableStateFlow(state)
                override val actions: LfoPanelActions = LfoPanelActions.EMPTY
            }

        @Composable
        fun feature(): LfoFeature =
            synthViewModel<LfoViewModel, LfoFeature>()
    }
}
