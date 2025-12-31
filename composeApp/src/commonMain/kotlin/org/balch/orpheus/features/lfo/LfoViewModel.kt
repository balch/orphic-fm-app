package org.balch.orpheus.features.lfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
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
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.ui.utils.PanelViewModel
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper

/** UI state for the Hyper LFO panel. */
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false
)

data class LfoPanelActions(
    val onLfoAChange: (Float) -> Unit,
    val onLfoBChange: (Float) -> Unit,
    val onModeChange: (HyperLfoMode) -> Unit,
    val onLinkChange: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = LfoPanelActions(
            onLfoAChange = {},
            onLfoBChange = {},
            onModeChange = {},
            onLinkChange = {}
        )
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

/**
 * ViewModel for the Hyper LFO panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(LfoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LfoViewModel(
    private val engine: SynthEngine,
    private val presetLoader: PresetLoader,
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), PanelViewModel<LfoUiState, LfoPanelActions> {

    override val panelActions = LfoPanelActions(
        onLfoAChange = ::onLfoAChange,
        onLfoBChange = ::onLfoBChange,
        onModeChange = ::onModeChange,
        onLinkChange = ::onLinkChange
    )

    private val intents =
        MutableSharedFlow<LfoIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    override val uiState: StateFlow<LfoUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(LfoUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LfoUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            applyFullState(uiState.value)

            launch {
                presetLoader.presetFlow.collect { preset ->
                    val lfoState =
                        LfoUiState(
                            lfoA = preset.hyperLfoA,
                            lfoB = preset.hyperLfoB,
                            mode =preset.hyperLfoMode,
                            linkEnabled = preset.hyperLfoLink
                        )
                    intents.tryEmit(LfoIntent.Restore(lfoState))
                }
            }

            // Subscribe to control changes for LFO controls
            launch {
                synthController.onControlChange.collect { event ->
                    val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
                    when (event.controlId) {
                        ControlIds.HYPER_LFO_A -> intents.tryEmit(LfoIntent.LfoA(event.value, fromSequencer))
                        ControlIds.HYPER_LFO_B -> intents.tryEmit(LfoIntent.LfoB(event.value, fromSequencer))
                        ControlIds.HYPER_LFO_MODE -> {
                            val modes = HyperLfoMode.entries.toTypedArray()
                            val index = (event.value * (modes.size - 1)).toInt().coerceIn(0, modes.size - 1)
                            intents.tryEmit(LfoIntent.Mode(modes[index]))
                        }
                        ControlIds.HYPER_LFO_LINK -> intents.tryEmit(LfoIntent.Link(event.value >= 0.5f))
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: LfoUiState, intent: LfoIntent): LfoUiState =
        when (intent) {
            is LfoIntent.LfoA ->
                state.copy(lfoA = intent.value)

            is LfoIntent.LfoB ->
                state.copy(lfoB = intent.value)

            is LfoIntent.Mode ->
                state.copy(mode = intent.mode)

            is LfoIntent.Link -> state.copy(linkEnabled = intent.enabled)
            is LfoIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: LfoIntent) {
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

    fun onLfoAChange(value: Float) {
        intents.tryEmit(LfoIntent.LfoA(value))
    }

    fun onLfoBChange(value: Float) {
        intents.tryEmit(LfoIntent.LfoB(value))
    }

    fun onModeChange(mode: HyperLfoMode) {
        intents.tryEmit(LfoIntent.Mode(mode))
    }

    fun onLinkChange(enabled: Boolean) {
        intents.tryEmit(LfoIntent.Link(enabled))
    }

    fun restoreState(state: LfoUiState) {
        intents.tryEmit(LfoIntent.Restore(state))
    }

    companion object {
        val PREVIEW = ViewModelStateActionMapper(
            state = LfoUiState(),
            actions = LfoPanelActions.EMPTY,
        )
    }
}
