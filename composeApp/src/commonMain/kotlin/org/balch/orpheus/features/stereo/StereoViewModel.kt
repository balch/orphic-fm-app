package org.balch.orpheus.features.stereo

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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.ui.utils.PanelViewModel
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper

/** UI state for the Stereo panel. */
data class StereoUiState(
    val mode: StereoMode = StereoMode.VOICE_PAN,
    val masterPan: Float = 0f,  // -1=Left, 0=Center, 1=Right
    val voicePans: List<Float> = listOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f)
)

data class StereoPanelActions(
    val onModeChange: (StereoMode) -> Unit,
    val onMasterPanChange: (Float) -> Unit
) {
    companion object {
        val EMPTY = StereoPanelActions(
            onModeChange = {},
            onMasterPanChange = {}
        )
    }
}

/** User intents for the Stereo panel. */
private sealed interface StereoIntent {
    data class SetMode(val mode: StereoMode) : StereoIntent
    data class SetMasterPan(val pan: Float) : StereoIntent
    data class SetVoicePan(val index: Int, val pan: Float) : StereoIntent
    data class Restore(val state: StereoUiState) : StereoIntent
}

/**
 * ViewModel for the Stereo panel.
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 */
@Inject
@ViewModelKey(StereoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StereoViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel(), PanelViewModel<StereoUiState, StereoPanelActions> {

    override val panelActions = StereoPanelActions(
        onModeChange = ::onModeChange,
        onMasterPanChange = ::onMasterPanChange
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<StereoIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Control changes -> StereoIntent
    private val controlIntents = synthController.onControlChange.map { event ->
        when (event.controlId) {
            ControlIds.STEREO_PAN -> {
                // Convert 0-1 to -1..1 for pan
                StereoIntent.SetMasterPan((event.value * 2f) - 1f)
            }
            ControlIds.STEREO_MODE -> {
                StereoIntent.SetMode(
                    if (event.value >= 0.5f) StereoMode.STEREO_DELAYS else StereoMode.VOICE_PAN
                )
            }
            else -> null
        }
    }

    override val uiState: StateFlow<StereoUiState> = flow {
        // Emit initial state from engine
        val initial = loadInitialState()
        applyFullState(initial)
        emit(initial)
        
        // Then emit from merged intent sources
        emitAll(
            merge(_userIntents, controlIntents)
                .onEach { intent -> if (intent != null) applyToEngine(intent) }
                .scan(initial) { state, intent -> if (intent != null) reduce(state, intent) else state }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StereoUiState()
    )

    private fun loadInitialState(): StereoUiState {
        return StereoUiState(
            mode = engine.getStereoMode(),
            masterPan = engine.getMasterPan(),
            voicePans = List(8) { engine.getVoicePan(it) }
        )
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: StereoUiState, intent: StereoIntent): StereoUiState =
        when (intent) {
            is StereoIntent.SetMode -> state.copy(mode = intent.mode)
            is StereoIntent.SetMasterPan -> state.copy(masterPan = intent.pan)
            is StereoIntent.SetVoicePan -> {
                val newPans = state.voicePans.toMutableList()
                newPans[intent.index] = intent.pan
                state.copy(voicePans = newPans)
            }
            is StereoIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: StereoIntent) {
        when (intent) {
            is StereoIntent.SetMode -> engine.setStereoMode(intent.mode)
            is StereoIntent.SetMasterPan -> engine.setMasterPan(intent.pan)
            is StereoIntent.SetVoicePan -> engine.setVoicePan(intent.index, intent.pan)
            is StereoIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: StereoUiState) {
        engine.setStereoMode(state.mode)
        engine.setMasterPan(state.masterPan)
        state.voicePans.forEachIndexed { index, pan ->
            engine.setVoicePan(index, pan)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onModeChange(mode: StereoMode) {
        _userIntents.tryEmit(StereoIntent.SetMode(mode))
    }

    fun onMasterPanChange(pan: Float) {
        _userIntents.tryEmit(StereoIntent.SetMasterPan(pan))
    }

    fun onVoicePanChange(index: Int, pan: Float) {
        _userIntents.tryEmit(StereoIntent.SetVoicePan(index, pan))
    }

    fun restoreState(state: StereoUiState) {
        _userIntents.tryEmit(StereoIntent.Restore(state))
    }

    companion object {
        val PREVIEW = ViewModelStateActionMapper(
            state = StereoUiState(),
            actions = StereoPanelActions.EMPTY,
        )
    }
}
