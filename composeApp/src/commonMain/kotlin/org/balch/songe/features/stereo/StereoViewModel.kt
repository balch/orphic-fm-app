package org.balch.songe.features.stereo

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
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.audio.StereoMode
import org.balch.songe.core.coroutines.DispatcherProvider

/** UI state for the Stereo panel. */
data class StereoUiState(
    val mode: StereoMode = StereoMode.VOICE_PAN,
    val masterPan: Float = 0f,  // -1=Left, 0=Center, 1=Right
    val voicePans: List<Float> = listOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f)
)

/** User intents for the Stereo panel. */
private sealed interface StereoIntent {
    data class SetMode(val mode: StereoMode) : StereoIntent
    data class SetMasterPan(val pan: Float) : StereoIntent
    data class SetVoicePan(val index: Int, val pan: Float) : StereoIntent
    data class Restore(val state: StereoUiState) : StereoIntent
}

/**
 * ViewModel for the Stereo panel.
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(StereoViewModel::class)
@ContributesIntoMap(AppScope::class)
class StereoViewModel(
    private val engine: SongeEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents = MutableSharedFlow<StereoIntent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiState: StateFlow<StereoUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(StereoUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = StereoUiState()
            )

    init {
        viewModelScope.launch(dispatcherProvider.io) {
            // Apply initial state to engine
            applyFullState(uiState.value)
        }
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
        intents.tryEmit(StereoIntent.SetMode(mode))
    }

    fun onMasterPanChange(pan: Float) {
        intents.tryEmit(StereoIntent.SetMasterPan(pan))
    }

    fun onVoicePanChange(index: Int, pan: Float) {
        intents.tryEmit(StereoIntent.SetVoicePan(index, pan))
    }

    fun restoreState(state: StereoUiState) {
        intents.tryEmit(StereoIntent.Restore(state))
    }
}
