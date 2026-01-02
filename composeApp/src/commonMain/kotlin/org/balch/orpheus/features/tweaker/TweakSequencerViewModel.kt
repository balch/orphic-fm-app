package org.balch.orpheus.features.tweaker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.ui.utils.PanelViewModel
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper
import org.balch.orpheus.util.currentTimeMillis

/**
 * State for the unified parameter sequencer.
 */
data class TweakSequencerState(
    val config: TweakSequencerConfig = TweakSequencerConfig(),
    val paths: Map<TweakSequencerParameter, SequencerPath> = emptyMap(),
    val currentPosition: Float = 0f,
    val isPlaying: Boolean = false,
    val playDirection: Int = 1  // 1 = forward, -1 = backward (for ping-pong)
)

/**
 * UI state for the TweakSequencer feature.
 */
data class TweakSequencerUiState(
    val sequencer: TweakSequencerState = TweakSequencerState(),
    val isExpanded: Boolean = false,
    val activeParameter: TweakSequencerParameter? = null  // Currently selected for drawing
)

/**
 * User intents for the TweakSequencer feature.
 */
sealed interface TweakSequencerIntent {
    // Playback
    data object Play : TweakSequencerIntent
    data object Pause : TweakSequencerIntent
    data object Stop : TweakSequencerIntent
    data class UpdatePosition(val position: Float, val direction: Int) : TweakSequencerIntent

    // Drawing
    data class StartPath(val param: TweakSequencerParameter, val point: SequencerPoint) : TweakSequencerIntent
    data class AddPoint(val param: TweakSequencerParameter, val point: SequencerPoint) : TweakSequencerIntent
    data class RemovePointsAfter(val param: TweakSequencerParameter, val time: Float) : TweakSequencerIntent
    data class ClearPath(val param: TweakSequencerParameter) : TweakSequencerIntent
    data class CompletePath(val param: TweakSequencerParameter, val endValue: Float) : TweakSequencerIntent

    // Parameter selection
    data class AddParameter(val param: TweakSequencerParameter) : TweakSequencerIntent
    data class RemoveParameter(val param: TweakSequencerParameter) : TweakSequencerIntent
    data class SelectActiveParameter(val param: TweakSequencerParameter?) : TweakSequencerIntent

    // Configuration
    data class SetDuration(val seconds: Float) : TweakSequencerIntent
    data class SetPlaybackMode(val mode: TweakPlaybackMode) : TweakSequencerIntent
    data class SetEnabled(val enabled: Boolean) : TweakSequencerIntent

    // UI
    data object Expand : TweakSequencerIntent
    data object Collapse : TweakSequencerIntent
    data object Save : TweakSequencerIntent
    data object Cancel : TweakSequencerIntent
}

/**
 * ViewModel for the TweakSequencer feature.
 *
 * Manages a single unified sequencer with up to 5 selectable parameters.
 * Uses MVI pattern with intents flowing through a reducer to produce state.
 */
@Inject
@ViewModelKey(TweakSequencerViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TweakSequencerViewModel(
    private val dispatcherProvider: DispatcherProvider,
    private val synthController: SynthController,
    private val engine: SynthEngine
) : ViewModel(), PanelViewModel<TweakSequencerUiState, TweakSequencerPanelActions> {

    private val intents = MutableSharedFlow<TweakSequencerIntent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val uiState: StateFlow<TweakSequencerUiState> =
        intents
            .onEach { intent -> applySideEffects(intent) }
            .scan(TweakSequencerUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = TweakSequencerUiState()
            )

    override val panelActions: TweakSequencerPanelActions = TweakSequencerPanelActions(
        onPlay = { intents.tryEmit(TweakSequencerIntent.Play) },
        onPause = { intents.tryEmit(TweakSequencerIntent.Pause) },
        onStop = { intents.tryEmit(TweakSequencerIntent.Stop) },
        onTogglePlayPause = {
            if (uiState.value.sequencer.isPlaying) intents.tryEmit(TweakSequencerIntent.Pause)
            else intents.tryEmit(TweakSequencerIntent.Play)
        },
        onStartPath = { param, point -> intents.tryEmit(TweakSequencerIntent.StartPath(param, point)) },
        onAddPoint = { param, point -> intents.tryEmit(TweakSequencerIntent.AddPoint(param, point)) },
        onRemovePointsAfter = { param, time -> intents.tryEmit(TweakSequencerIntent.RemovePointsAfter(param, time)) },
        onClearPath = { param -> intents.tryEmit(TweakSequencerIntent.ClearPath(param)) },
        onCompletePath = { param, endValue -> intents.tryEmit(TweakSequencerIntent.CompletePath(param, endValue)) },
        onAddParameter = { param -> intents.tryEmit(TweakSequencerIntent.AddParameter(param)) },
        onRemoveParameter = { param -> intents.tryEmit(TweakSequencerIntent.RemoveParameter(param)) },
        onSelectActiveParameter = { param -> intents.tryEmit(TweakSequencerIntent.SelectActiveParameter(param)) },
        onSetDuration = { seconds -> intents.tryEmit(TweakSequencerIntent.SetDuration(seconds)) },
        onSetPlaybackMode = { mode -> intents.tryEmit(TweakSequencerIntent.SetPlaybackMode(mode)) },
        onSetEnabled = { enabled -> intents.tryEmit(TweakSequencerIntent.SetEnabled(enabled)) },
        onExpand = { intents.tryEmit(TweakSequencerIntent.Expand) },
        onCollapse = { intents.tryEmit(TweakSequencerIntent.Collapse) },
        onSave = { intents.tryEmit(TweakSequencerIntent.Save) },
        onCancel = { intents.tryEmit(TweakSequencerIntent.Cancel) }
    )

    private var playbackJob: Job? = null

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: TweakSequencerUiState, intent: TweakSequencerIntent): TweakSequencerUiState {
        return when (intent) {
            is TweakSequencerIntent.Play -> state.updateSequencer {
                it.copy(isPlaying = true)
            }

            is TweakSequencerIntent.Pause -> state.updateSequencer {
                it.copy(isPlaying = false)
            }

            is TweakSequencerIntent.Stop -> state.updateSequencer {
                it.copy(isPlaying = false, currentPosition = 0f, playDirection = 1)
            }

            is TweakSequencerIntent.UpdatePosition -> state.updateSequencer {
                it.copy(currentPosition = intent.position, playDirection = intent.direction)
            }

            is TweakSequencerIntent.StartPath -> state.updateSequencer {
                val path = it.paths[intent.param] ?: SequencerPath()
                val newPath = path.startedAt(intent.point)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakSequencerIntent.AddPoint -> state.updateSequencer {
                val path = it.paths[intent.param] ?: SequencerPath()
                val newPath = path.withPointAdded(intent.point)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakSequencerIntent.RemovePointsAfter -> state.updateSequencer {
                val path = it.paths[intent.param] ?: SequencerPath()
                val newPath = path.withPointsRemovedAfter(intent.time)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakSequencerIntent.ClearPath -> state.updateSequencer {
                it.copy(paths = it.paths + (intent.param to SequencerPath()))
            }

            is TweakSequencerIntent.CompletePath -> state.updateSequencer {
                val path = it.paths[intent.param] ?: SequencerPath()
                // Apply smoothing when the path is completed to reduce jitter
                val newPath = path.completed(intent.endValue).smoothed()
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakSequencerIntent.AddParameter -> {
                val currentParams = state.sequencer.config.selectedParameters
                if (currentParams.size >= TweakSequencerParameter.MAX_SELECTED || intent.param in currentParams) {
                    state
                } else {
                    state.updateSequencer {
                        it.copy(
                            config = it.config.copy(selectedParameters = currentParams + intent.param),
                            paths = it.paths + (intent.param to SequencerPath())
                        )
                    }
                }
            }

            is TweakSequencerIntent.RemoveParameter -> state.updateSequencer {
                it.copy(
                    config = it.config.copy(
                        selectedParameters = it.config.selectedParameters - intent.param
                    ),
                    paths = it.paths - intent.param
                )
            }

            is TweakSequencerIntent.SelectActiveParameter -> state.copy(activeParameter = intent.param)

            is TweakSequencerIntent.SetDuration -> state.updateSequencer {
                it.copy(config = it.config.copy(durationSeconds = intent.seconds.coerceIn(
                    TweakSequencerConfig.MIN_DURATION,
                    TweakSequencerConfig.MAX_DURATION
                )))
            }

            is TweakSequencerIntent.SetPlaybackMode -> state.updateSequencer {
                it.copy(config = it.config.copy(tweakPlaybackMode = intent.mode))
            }

            is TweakSequencerIntent.SetEnabled -> state.updateSequencer {
                it.copy(config = it.config.copy(enabled = intent.enabled))
            }

            is TweakSequencerIntent.Expand -> state.copy(isExpanded = true)
            is TweakSequencerIntent.Collapse -> state.copy(isExpanded = false)
            is TweakSequencerIntent.Save -> state.copy(isExpanded = false)
            is TweakSequencerIntent.Cancel -> state.copy(isExpanded = false)
        }.autoUpdateEnabled()
    }

    /**
     * Auto-enable/disable based on whether there are any paths with points.
     * No manual toggle needed - having paths = enabled.
     */
    private fun TweakSequencerUiState.autoUpdateEnabled(): TweakSequencerUiState {
        val hasAnyPath = sequencer.paths.values.any { it.points.isNotEmpty() }
        return if (sequencer.config.enabled != hasAnyPath) {
            copy(sequencer = sequencer.copy(
                config = sequencer.config.copy(enabled = hasAnyPath)
            ))
        } else {
            this
        }
    }

    private fun TweakSequencerUiState.updateSequencer(
        update: (TweakSequencerState) -> TweakSequencerState
    ): TweakSequencerUiState = copy(sequencer = update(sequencer))

    // ═══════════════════════════════════════════════════════════
    // SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applySideEffects(intent: TweakSequencerIntent) {
        when (intent) {
            is TweakSequencerIntent.Play -> startPlayback()
            is TweakSequencerIntent.Pause -> stopPlayback()
            is TweakSequencerIntent.Stop -> stopPlayback()
            is TweakSequencerIntent.UpdatePosition -> applyParameterValues()
            is TweakSequencerIntent.SetDuration, is TweakSequencerIntent.SetPlaybackMode -> restartIfPlaying()
            else -> { /* No side effects */ }
        }
    }
    
    private fun restartIfPlaying() {
        if (uiState.value.sequencer.isPlaying) {
            startPlayback()
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        
        val state = uiState.value.sequencer
        if (!state.config.enabled) return
        
        // Push DSP automation to engine for audio-rate precision
        val mode = when (state.config.tweakPlaybackMode) {
            TweakPlaybackMode.ONCE -> 0
            TweakPlaybackMode.LOOP -> 1
            TweakPlaybackMode.PING_PONG -> 2
        }
        
        state.paths.forEach { (param, path) ->
            if (isDspParam(param) && path.points.isNotEmpty()) {
                getControlId(param)?.let { id ->
                    val times = FloatArray(path.points.size)
                    val values = FloatArray(path.points.size)
                    path.points.forEachIndexed { i, p ->
                        times[i] = p.time
                        values[i] = p.value
                    }
                    engine.setParameterAutomation(
                        id, 
                        times, 
                        values, 
                        path.points.size, 
                        state.config.durationSeconds, 
                        mode
                    )
                }
            }
        }

        // Start UI synchronization loop
        playbackJob = viewModelScope.launch(dispatcherProvider.io) {
            val tickMs = 32L  // ~30fps for UI
            var lastTime = currentTimeMillis()
            
            while (isActive) {
                val currentState = uiState.value.sequencer
                if (!currentState.isPlaying || !currentState.config.enabled) {
                    break // Job cancelled anyway
                }

                val now = currentTimeMillis()
                val deltaMs = now - lastTime
                lastTime = now

                val deltaNormalized = deltaMs / (currentState.config.durationSeconds * 1000f)
                var newPosition = currentState.currentPosition + deltaNormalized * currentState.playDirection
                var newDirection = currentState.playDirection

                // Logic matches Engine loop behavior approximately
                when (currentState.config.tweakPlaybackMode) {
                    TweakPlaybackMode.ONCE -> {
                        if (newPosition >= 1f) {
                            newPosition = 1f
                            intents.tryEmit(TweakSequencerIntent.Stop)
                        }
                    }
                    TweakPlaybackMode.LOOP -> {
                        if (newPosition >= 1f) newPosition = 0f
                    }
                    TweakPlaybackMode.PING_PONG -> {
                        if (newPosition >= 1f) { newPosition = 1f; newDirection = -1 }
                        else if (newPosition <= 0f) { newPosition = 0f; newDirection = 1 }
                    }
                }

                intents.tryEmit(TweakSequencerIntent.UpdatePosition(newPosition.coerceIn(0f, 1f), newDirection))
                delay(tickMs)
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        
        // Clear engine automation for DSP parameters
        uiState.value.sequencer.paths.keys.forEach { param ->
            if (isDspParam(param)) {
                getControlId(param)?.let { id ->
                    engine.clearParameterAutomation(id)
                }
            }
        }
    }

    private fun applyParameterValues() {
        val state = uiState.value.sequencer
        if (!state.config.enabled) return

        state.paths.forEach { (param, path) ->
            val value = path.valueAt(state.currentPosition) ?: return@forEach
            getControlId(param)?.let { id ->
                // Emit for ALL parameters with SEQUENCER origin for UI synchronization.
                // DSP params are driven by engine automation at audio-rate - the ViewModel
                // will receive this event for UI update but skip the engine call.
                synthController.emitControlChange(id, value, ControlEventOrigin.SEQUENCER)
            }
        }
    }
    
    private fun isDspParam(param: TweakSequencerParameter): Boolean {
        return param != TweakSequencerParameter.VIZ_KNOB_1 && param != TweakSequencerParameter.VIZ_KNOB_2
    }
    
    private fun getControlId(param: TweakSequencerParameter): String? {
        return when (param) {
            TweakSequencerParameter.LFO_FREQ_A -> ControlIds.HYPER_LFO_A
            TweakSequencerParameter.LFO_FREQ_B -> ControlIds.HYPER_LFO_B
            TweakSequencerParameter.DELAY_TIME_1 -> ControlIds.DELAY_TIME_1
            TweakSequencerParameter.DELAY_TIME_2 -> ControlIds.DELAY_TIME_2
            TweakSequencerParameter.DELAY_MOD_1 -> ControlIds.DELAY_MOD_1
            TweakSequencerParameter.DELAY_MOD_2 -> ControlIds.DELAY_MOD_2
            TweakSequencerParameter.DELAY_FEEDBACK -> ControlIds.DELAY_FEEDBACK
            TweakSequencerParameter.DELAY_MIX -> ControlIds.DELAY_MIX
            TweakSequencerParameter.DIST_DRIVE -> ControlIds.DRIVE
            TweakSequencerParameter.DIST_MIX -> ControlIds.DISTORTION_MIX
            TweakSequencerParameter.VIZ_KNOB_1 -> ControlIds.VIZ_KNOB_1
            TweakSequencerParameter.VIZ_KNOB_2 -> ControlIds.VIZ_KNOB_2
            TweakSequencerParameter.GLOB_VIBRATO -> ControlIds.VIBRATO
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS - KEEP FOR COMPATIBILITY IF NEEDED
    // ═══════════════════════════════════════════════════════════

    fun togglePlayPause() = panelActions.onTogglePlayPause()
    fun play() = panelActions.onPlay()
    fun pause() = panelActions.onPause()
    fun stop() = panelActions.onStop()
    fun expand() = panelActions.onExpand()
    fun cancel() = panelActions.onCancel()
    fun setPlaybackMode(mode: TweakPlaybackMode) = panelActions.onSetPlaybackMode(mode)

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
    
    companion object {
        val PREVIEW = ViewModelStateActionMapper(
            state = TweakSequencerUiState(),
            actions = TweakSequencerPanelActions(
                onPlay = {}, onPause = {}, onStop = {}, onTogglePlayPause = {},
                onStartPath = { _, _ -> }, onAddPoint = { _, _ -> }, onRemovePointsAfter = { _, _ -> },
                onClearPath = {}, onCompletePath = { _, _ -> },
                onAddParameter = {}, onRemoveParameter = {}, onSelectActiveParameter = {},
                onSetDuration = {}, onSetPlaybackMode = {}, onSetEnabled = {},
                onExpand = {}, onCollapse = {}, onSave = {}, onCancel = {}
            )
        )
    }
}

data class TweakSequencerPanelActions(
    val onPlay: () -> Unit,
    val onPause: () -> Unit,
    val onStop: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onStartPath: (TweakSequencerParameter, SequencerPoint) -> Unit,
    val onAddPoint: (TweakSequencerParameter, SequencerPoint) -> Unit,
    val onRemovePointsAfter: (TweakSequencerParameter, Float) -> Unit,
    val onClearPath: (TweakSequencerParameter) -> Unit,
    val onCompletePath: (TweakSequencerParameter, Float) -> Unit,
    val onAddParameter: (TweakSequencerParameter) -> Unit,
    val onRemoveParameter: (TweakSequencerParameter) -> Unit,
    val onSelectActiveParameter: (TweakSequencerParameter?) -> Unit,
    val onSetDuration: (Float) -> Unit,
    val onSetPlaybackMode: (TweakPlaybackMode) -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit
)
