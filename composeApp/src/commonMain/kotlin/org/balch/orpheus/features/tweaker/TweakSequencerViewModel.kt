package org.balch.orpheus.features.tweaker

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.synthViewModel

typealias TweakSequencerFeature = SynthFeature<TweakSequencerUiState, TweakSequencerPanelActions>

class TweakSequencerViewModel : ViewModel(), TweakSequencerFeature {
    private val _state = MutableStateFlow(TweakSequencerUiState())
    override val stateFlow: StateFlow<TweakSequencerUiState> = _state.asStateFlow()

    override val actions = TweakSequencerPanelActions(
        onExpand = { _state.value = _state.value.copy(isExpanded = true) },
        onCollapse = { _state.value = _state.value.copy(isExpanded = false) },
        onSave = { /* Save logic */ },
        onCancel = { _state.value = _state.value.copy(isExpanded = false) },
        onTogglePlayPause = {},
        onStop = {},
        onSetPlaybackMode = {},
        onSetDuration = {},
        onAddParameter = {},
        onRemoveParameter = {},
        onSelectActiveParameter = { _state.value = _state.value.copy(activeParameter = it) },
        onStartPath = { _, _ -> },
        onAddPoint = { _, _ -> },
        onRemovePointsAfter = { _, _ -> },
        onCompletePath = { _, _ -> },
        onClearPath = { _ -> },
        onSetEnabled = {}
    )
    
    companion object {
        fun previewFeature(state: TweakSequencerUiState = TweakSequencerUiState(isExpanded = true)): TweakSequencerFeature =
            object : TweakSequencerFeature {
                override val stateFlow: StateFlow<TweakSequencerUiState> = MutableStateFlow(state)
                override val actions: TweakSequencerPanelActions = TweakSequencerPanelActions(
                    onExpand = {}, onCollapse = {}, onSave = {}, onCancel = {},
                    onTogglePlayPause = {}, onStop = {}, onSetPlaybackMode = { _ -> },
                    onSetDuration = { _ -> }, onAddParameter = { _ -> }, onRemoveParameter = { _ -> },
                    onSelectActiveParameter = { _ -> },
                    onStartPath = { _, _ -> },
                    onAddPoint = { _, _ -> },
                    onRemovePointsAfter = { _, _ -> },
                    onCompletePath = { _, _ -> },
                    onClearPath = { _ -> },
                    onSetEnabled = { _ -> },
                    onPlay = {}, onPause = {}
                )
            }

        @Composable
        fun feature(): TweakSequencerFeature =
            synthViewModel<TweakSequencerViewModel, TweakSequencerFeature>()
    }
}

// === Data Definitions (Restored) ===

data class TweakSequencerUiState(
    val isExpanded: Boolean = false,
    val isPlaying: Boolean = false,
    val sequencer: TweakSequencerState = TweakSequencerState(),
    val activeParameter: TweakSequencerParameter? = null
)

data class TweakSequencerPanelActions(
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onTogglePlayPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSetPlaybackMode: (Any) -> Unit = {},
    val onSetDuration: (Float) -> Unit,
    val onAddParameter: (TweakSequencerParameter) -> Unit,
    val onRemoveParameter: (TweakSequencerParameter) -> Unit,
    val onSelectActiveParameter: (TweakSequencerParameter?) -> Unit,
    val onStartPath: (TweakSequencerParameter, SequencerPoint) -> Unit,
    val onAddPoint: (TweakSequencerParameter, SequencerPoint) -> Unit,
    val onRemovePointsAfter: (TweakSequencerParameter, Float) -> Unit,
    val onCompletePath: (TweakSequencerParameter, Float) -> Unit,
    val onClearPath: (TweakSequencerParameter) -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {}
)

data class TweakSequencerState(
    val isEnabled: Boolean = false,
    val duration: Float = 4.0f,
    val paths: Map<TweakSequencerParameter, SequencerPath> = emptyMap(),
    val playbackMode: TweakPlaybackMode = TweakPlaybackMode.LOOP,
    val isPlaying: Boolean = false,
    val currentPosition: Float = 0f,
    val config: TweakSequencerConfig = TweakSequencerConfig()
)

