package org.balch.orpheus.features.distortion

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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.SynthViewModel
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

/** UI state for the Distortion/Volume panel. */
data class DistortionUiState(
    val drive: Float = 0.0f,
    val volume: Float = 0.7f,
    val mix: Float = 0.5f,
    val peak: Float = 0.0f
)

data class DistortionPanelActions(
    val onDriveChange: (Float) -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onMixChange: (Float) -> Unit
) {
    companion object {
        val EMPTY = DistortionPanelActions(
            onDriveChange = {},
            onVolumeChange = {},
            onMixChange = {}
        )
    }
}

/** User intents for the Distortion panel. */
private sealed interface DistortionIntent {
    data class Drive(val value: Float, val fromSequencer: Boolean = false) : DistortionIntent
    data class Volume(val value: Float) : DistortionIntent
    data class Mix(val value: Float, val fromSequencer: Boolean = false) : DistortionIntent
    data class Peak(val value: Float) : DistortionIntent
    data class Restore(val state: DistortionUiState) : DistortionIntent
}

/**
 * ViewModel for the Distortion panel.
 *
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 */
@Inject
@ViewModelKey(DistortionViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DistortionViewModel(
    private val engine: SynthEngine,
    presetLoader: PresetLoader,
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), SynthViewModel<DistortionUiState, DistortionPanelActions> {

    override val actions = DistortionPanelActions(
        onDriveChange = ::onDriveChange,
        onVolumeChange = ::onVolumeChange,
        onMixChange = ::onMixChange
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<DistortionIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> DistortionIntent.Restore (preserving volume)
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        // Master Volume is NEVER set from presets - only user interaction
        DistortionIntent.Restore(
            DistortionUiState(
                drive = preset.drive,
                volume = engine.getMasterVolume(),  // Always preserve current
                mix = preset.distortionMix
            )
        )
    }

    // Peak level updates from engine
    private val peakIntents = engine.peakFlow.map { peak ->
        DistortionIntent.Peak(peak)
    }

    // Control changes -> DistortionIntent
    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.MASTER_VOLUME -> DistortionIntent.Volume(event.value)
            ControlIds.DRIVE -> DistortionIntent.Drive(event.value, fromSequencer)
            ControlIds.DISTORTION_MIX -> DistortionIntent.Mix(event.value, fromSequencer)
            else -> null
        }
    }

    override val stateFlow: StateFlow<DistortionUiState> = flow {
        // Emit initial state from engine
        val initial = loadInitialState()
        applyFullState(initial)
        emit(initial)
        
        // Then emit from merged intent sources
        emitAll(
            merge(_userIntents, presetIntents, peakIntents, controlIntents)
                .onEach { intent -> if (intent != null) applyToEngine(intent) }
                .scan(initial) { state, intent -> if (intent != null) reduce(state, intent) else state }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DistortionUiState()
    )

    private fun loadInitialState(): DistortionUiState {
        return DistortionUiState(
            drive = engine.getDrive(),
            volume = engine.getMasterVolume(),
            mix = engine.getDistortionMix(),
            peak = 0.0f
        )
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DistortionUiState, intent: DistortionIntent): DistortionUiState =
        when (intent) {
            is DistortionIntent.Drive -> state.copy(drive = intent.value)
            is DistortionIntent.Volume -> state.copy(volume = intent.value)
            is DistortionIntent.Mix -> state.copy(mix = intent.value)
            is DistortionIntent.Peak -> state.copy(peak = intent.value)
            is DistortionIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: DistortionIntent) {
        when (intent) {
            // Skip engine calls for SEQUENCER events - engine is driven by audio-rate automation
            is DistortionIntent.Drive -> if (!intent.fromSequencer) engine.setDrive(intent.value)
            is DistortionIntent.Volume -> engine.setMasterVolume(intent.value)
            is DistortionIntent.Mix -> if (!intent.fromSequencer) engine.setDistortionMix(intent.value)
            is DistortionIntent.Peak -> { /* Peak is read-only from engine */ }
            is DistortionIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: DistortionUiState) {
        engine.setDrive(state.drive)
        engine.setMasterVolume(state.volume)
        engine.setDistortionMix(state.mix)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onDriveChange(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Drive(value))
    }

    fun onVolumeChange(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Volume(value))
    }

    fun onMixChange(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Mix(value))
    }

    fun updatePeak(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Peak(value))
    }

    fun restoreState(state: DistortionUiState) {
        _userIntents.tryEmit(DistortionIntent.Restore(state))
    }

    companion object {
        val PREVIEW_STATE = MutableStateFlow(DistortionUiState())
        val PREVIEW_ACTIONS = DistortionPanelActions.EMPTY

        fun previewFeature(state: DistortionUiState = DistortionUiState()) =
            object : SynthFeature<DistortionUiState, DistortionPanelActions> {
                override val stateFlow: StateFlow<DistortionUiState> = MutableStateFlow(state)
                override val actions: DistortionPanelActions = DistortionPanelActions.EMPTY
            }

        @Composable
        fun panelFeature(): SynthFeature<DistortionUiState, DistortionPanelActions> =
            synthViewModel<DistortionViewModel, DistortionUiState, DistortionPanelActions>()
    }
}
