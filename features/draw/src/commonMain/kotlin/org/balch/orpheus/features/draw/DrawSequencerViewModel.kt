package org.balch.orpheus.features.draw

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.synthViewModel

interface DrawSequencerFeature: SynthFeature<DrawSequencerUiState, DrawSequencerPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

@Inject
@ViewModelKey(DrawSequencerViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DrawSequencerViewModel : ViewModel(), DrawSequencerFeature {
    private val _state = MutableStateFlow(DrawSequencerUiState())
    override val stateFlow: StateFlow<DrawSequencerUiState> = _state.asStateFlow()

    override val actions = DrawSequencerPanelActions(
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
    
    companion object Companion {
        fun previewFeature(state: DrawSequencerUiState = DrawSequencerUiState(isExpanded = true)): DrawSequencerFeature =
            object : DrawSequencerFeature {
                override val stateFlow: StateFlow<DrawSequencerUiState> = MutableStateFlow(state)
                override val actions: DrawSequencerPanelActions = DrawSequencerPanelActions(
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
        fun feature(): DrawSequencerFeature =
            synthViewModel<DrawSequencerViewModel, DrawSequencerFeature>()
    }
}

// === Data Definitions (Restored) ===

data class DrawSequencerUiState(
    val isExpanded: Boolean = false,
    val isPlaying: Boolean = false,
    val sequencer: DrawSequencerState = DrawSequencerState(),
    val activeParameter: DrawSequencerParameter? = null
)

data class DrawSequencerPanelActions(
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onTogglePlayPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSetPlaybackMode: (Any) -> Unit = {},
    val onSetDuration: (Float) -> Unit,
    val onAddParameter: (DrawSequencerParameter) -> Unit,
    val onRemoveParameter: (DrawSequencerParameter) -> Unit,
    val onSelectActiveParameter: (DrawSequencerParameter?) -> Unit,
    val onStartPath: (DrawSequencerParameter, SequencerPoint) -> Unit,
    val onAddPoint: (DrawSequencerParameter, SequencerPoint) -> Unit,
    val onRemovePointsAfter: (DrawSequencerParameter, Float) -> Unit,
    val onCompletePath: (DrawSequencerParameter, Float) -> Unit,
    val onClearPath: (DrawSequencerParameter) -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {}
)

data class DrawSequencerState(
    val isEnabled: Boolean = false,
    val duration: Float = 4.0f,
    val paths: Map<DrawSequencerParameter, SequencerPath> = emptyMap(),
    val playbackMode: DrawSequencerPlaybackMode = DrawSequencerPlaybackMode.LOOP,
    val isPlaying: Boolean = false,
    val currentPosition: Float = 0f,
    val config: DrawSequencerConfig = DrawSequencerConfig()
)

