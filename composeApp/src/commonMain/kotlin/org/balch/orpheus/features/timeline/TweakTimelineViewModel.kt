package org.balch.orpheus.features.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
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
import org.balch.orpheus.util.currentTimeMillis

/**
 * State for the unified parameter timeline.
 */
data class TweakTimelineState(
    val config: TweakTimelineConfig = TweakTimelineConfig(),
    val paths: Map<TweakTimelineParameter, TimelinePath> = emptyMap(),
    val currentPosition: Float = 0f,
    val isPlaying: Boolean = false,
    val playDirection: Int = 1  // 1 = forward, -1 = backward (for ping-pong)
)

/**
 * UI state for the ParamTimeline feature.
 */
data class TweakTimelineUiState(
    val timeline: TweakTimelineState = TweakTimelineState(),
    val isExpanded: Boolean = false,
    val activeParameter: TweakTimelineParameter? = null  // Currently selected for drawing
)

/**
 * User intents for the ParamTimeline feature.
 */
sealed interface TweakTimelineIntent {
    // Playback
    data object Play : TweakTimelineIntent
    data object Pause : TweakTimelineIntent
    data object Stop : TweakTimelineIntent
    data class UpdatePosition(val position: Float, val direction: Int) : TweakTimelineIntent

    // Drawing
    data class StartPath(val param: TweakTimelineParameter, val point: TimelinePoint) : TweakTimelineIntent
    data class AddPoint(val param: TweakTimelineParameter, val point: TimelinePoint) : TweakTimelineIntent
    data class RemovePointsAfter(val param: TweakTimelineParameter, val time: Float) : TweakTimelineIntent
    data class ClearPath(val param: TweakTimelineParameter) : TweakTimelineIntent
    data class CompletePath(val param: TweakTimelineParameter, val endValue: Float) : TweakTimelineIntent

    // Parameter selection
    data class AddParameter(val param: TweakTimelineParameter) : TweakTimelineIntent
    data class RemoveParameter(val param: TweakTimelineParameter) : TweakTimelineIntent
    data class SelectActiveParameter(val param: TweakTimelineParameter?) : TweakTimelineIntent

    // Configuration
    data class SetDuration(val seconds: Float) : TweakTimelineIntent
    data class SetPlaybackMode(val mode: TweakPlaybackMode) : TweakTimelineIntent
    data class SetEnabled(val enabled: Boolean) : TweakTimelineIntent

    // UI
    data object Expand : TweakTimelineIntent
    data object Collapse : TweakTimelineIntent
    data object Save : TweakTimelineIntent
    data object Cancel : TweakTimelineIntent
}

/**
 * ViewModel for the ParamTimeline feature.
 *
 * Manages a single unified timeline with up to 5 selectable parameters.
 * Uses MVI pattern with intents flowing through a reducer to produce state.
 */
@Inject
@ViewModelKey(TweakTimelineViewModel::class)
@ContributesIntoMap(AppScope::class)
class TweakTimelineViewModel(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents = MutableSharedFlow<TweakTimelineIntent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiState: StateFlow<TweakTimelineUiState> =
        intents
            .onEach { intent -> applySideEffects(intent) }
            .scan(TweakTimelineUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = TweakTimelineUiState()
            )

    private var playbackJob: Job? = null

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: TweakTimelineUiState, intent: TweakTimelineIntent): TweakTimelineUiState {
        return when (intent) {
            is TweakTimelineIntent.Play -> state.updateTimeline {
                it.copy(isPlaying = true)
            }

            is TweakTimelineIntent.Pause -> state.updateTimeline {
                it.copy(isPlaying = false)
            }

            is TweakTimelineIntent.Stop -> state.updateTimeline {
                it.copy(isPlaying = false, currentPosition = 0f, playDirection = 1)
            }

            is TweakTimelineIntent.UpdatePosition -> state.updateTimeline {
                it.copy(currentPosition = intent.position, playDirection = intent.direction)
            }

            is TweakTimelineIntent.StartPath -> state.updateTimeline {
                val path = it.paths[intent.param] ?: TimelinePath()
                val newPath = path.startedAt(intent.point)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakTimelineIntent.AddPoint -> state.updateTimeline {
                val path = it.paths[intent.param] ?: TimelinePath()
                val newPath = path.withPointAdded(intent.point)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakTimelineIntent.RemovePointsAfter -> state.updateTimeline {
                val path = it.paths[intent.param] ?: TimelinePath()
                val newPath = path.withPointsRemovedAfter(intent.time)
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakTimelineIntent.ClearPath -> state.updateTimeline {
                it.copy(paths = it.paths + (intent.param to TimelinePath()))
            }

            is TweakTimelineIntent.CompletePath -> state.updateTimeline {
                val path = it.paths[intent.param] ?: TimelinePath()
                // Apply smoothing when the path is completed to reduce jitter
                val newPath = path.completed(intent.endValue).smoothed()
                it.copy(paths = it.paths + (intent.param to newPath))
            }

            is TweakTimelineIntent.AddParameter -> {
                val currentParams = state.timeline.config.selectedParameters
                if (currentParams.size >= TweakTimelineParameter.MAX_SELECTED || intent.param in currentParams) {
                    state
                } else {
                    state.updateTimeline {
                        it.copy(
                            config = it.config.copy(selectedParameters = currentParams + intent.param),
                            paths = it.paths + (intent.param to TimelinePath())
                        )
                    }
                }
            }

            is TweakTimelineIntent.RemoveParameter -> state.updateTimeline {
                it.copy(
                    config = it.config.copy(
                        selectedParameters = it.config.selectedParameters - intent.param
                    ),
                    paths = it.paths - intent.param
                )
            }

            is TweakTimelineIntent.SelectActiveParameter -> state.copy(activeParameter = intent.param)

            is TweakTimelineIntent.SetDuration -> state.updateTimeline {
                it.copy(config = it.config.copy(durationSeconds = intent.seconds.coerceIn(
                    TweakTimelineConfig.MIN_DURATION,
                    TweakTimelineConfig.MAX_DURATION
                )))
            }

            is TweakTimelineIntent.SetPlaybackMode -> state.updateTimeline {
                it.copy(config = it.config.copy(tweakPlaybackMode = intent.mode))
            }

            is TweakTimelineIntent.SetEnabled -> state.updateTimeline {
                it.copy(config = it.config.copy(enabled = intent.enabled))
            }

            is TweakTimelineIntent.Expand -> state.copy(isExpanded = true)
            is TweakTimelineIntent.Collapse -> state.copy(isExpanded = false)
            is TweakTimelineIntent.Save -> state.copy(isExpanded = false)
            is TweakTimelineIntent.Cancel -> state.copy(isExpanded = false)
        }
    }

    private fun TweakTimelineUiState.updateTimeline(
        update: (TweakTimelineState) -> TweakTimelineState
    ): TweakTimelineUiState = copy(timeline = update(timeline))

    // ═══════════════════════════════════════════════════════════
    // SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applySideEffects(intent: TweakTimelineIntent) {
        when (intent) {
            is TweakTimelineIntent.Play -> startPlayback()
            is TweakTimelineIntent.Pause -> stopPlayback()
            is TweakTimelineIntent.Stop -> stopPlayback()
            is TweakTimelineIntent.UpdatePosition -> applyParameterValues()
            else -> { /* No side effects */ }
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()

        playbackJob = viewModelScope.launch(dispatcherProvider.io) {
            val tickMs = 16L  // ~60fps
            var lastTime = currentTimeMillis()

            while (isActive) {
                val currentState = uiState.value.timeline
                if (!currentState.isPlaying || !currentState.config.enabled) {
                    delay(tickMs)
                    continue
                }

                val now = currentTimeMillis()
                val deltaMs = now - lastTime
                lastTime = now

                val deltaNormalized = deltaMs / (currentState.config.durationSeconds * 1000f)
                var newPosition = currentState.currentPosition + deltaNormalized * currentState.playDirection
                var newDirection = currentState.playDirection

                when (currentState.config.tweakPlaybackMode) {
                    TweakPlaybackMode.ONCE -> {
                        if (newPosition >= 1f) {
                            newPosition = 1f
                            intents.tryEmit(TweakTimelineIntent.Stop)
                        }
                    }

                    TweakPlaybackMode.LOOP -> {
                        if (newPosition >= 1f) {
                            newPosition = 0f
                        }
                    }

                    TweakPlaybackMode.PING_PONG -> {
                        if (newPosition >= 1f) {
                            newPosition = 1f
                            newDirection = -1
                        } else if (newPosition <= 0f) {
                            newPosition = 0f
                            newDirection = 1
                        }
                    }
                }

                intents.tryEmit(TweakTimelineIntent.UpdatePosition(newPosition.coerceIn(0f, 1f), newDirection))
                delay(tickMs)
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun applyParameterValues() {
        val state = uiState.value.timeline
        if (!state.config.enabled) return

        state.paths.forEach { (param, path) ->
            val value = path.valueAt(state.currentPosition) ?: return@forEach

            when (param) {
                TweakTimelineParameter.LFO_FREQ_A -> engine.setHyperLfoFreq(0, value)
                TweakTimelineParameter.LFO_FREQ_B -> engine.setHyperLfoFreq(1, value)
                TweakTimelineParameter.DELAY_TIME_1 -> engine.setDelayTime(0, value)
                TweakTimelineParameter.DELAY_TIME_2 -> engine.setDelayTime(1, value)
                TweakTimelineParameter.DELAY_MOD_1 -> engine.setDelayModDepth(0, value)
                TweakTimelineParameter.DELAY_MOD_2 -> engine.setDelayModDepth(1, value)
                TweakTimelineParameter.DELAY_FEEDBACK -> engine.setDelayFeedback(value)
                TweakTimelineParameter.DELAY_MIX -> engine.setDelayMix(value)
                TweakTimelineParameter.DIST_DRIVE -> engine.setDrive(value)
                TweakTimelineParameter.DIST_MIX -> engine.setDistortionMix(value)
                TweakTimelineParameter.VIZ_KNOB_1 -> { /* TODO: Hook to viz controls */ }
                TweakTimelineParameter.VIZ_KNOB_2 -> { /* TODO: Hook to viz controls */ }
                TweakTimelineParameter.GLOB_VIBRATO -> engine.setVibrato(value)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun play() = intents.tryEmit(TweakTimelineIntent.Play)
    fun pause() = intents.tryEmit(TweakTimelineIntent.Pause)
    fun stop() = intents.tryEmit(TweakTimelineIntent.Stop)
    fun togglePlayPause() {
        if (uiState.value.timeline.isPlaying) pause() else play()
    }

    fun startPath(param: TweakTimelineParameter, point: TimelinePoint) =
        intents.tryEmit(TweakTimelineIntent.StartPath(param, point))
    fun addPoint(param: TweakTimelineParameter, point: TimelinePoint) =
        intents.tryEmit(TweakTimelineIntent.AddPoint(param, point))
    fun removePointsAfter(param: TweakTimelineParameter, time: Float) =
        intents.tryEmit(TweakTimelineIntent.RemovePointsAfter(param, time))
    fun clearPath(param: TweakTimelineParameter) =
        intents.tryEmit(TweakTimelineIntent.ClearPath(param))
    fun completePath(param: TweakTimelineParameter, endValue: Float) =
        intents.tryEmit(TweakTimelineIntent.CompletePath(param, endValue))

    fun addParameter(param: TweakTimelineParameter) =
        intents.tryEmit(TweakTimelineIntent.AddParameter(param))
    fun removeParameter(param: TweakTimelineParameter) =
        intents.tryEmit(TweakTimelineIntent.RemoveParameter(param))
    fun selectActiveParameter(param: TweakTimelineParameter?) =
        intents.tryEmit(TweakTimelineIntent.SelectActiveParameter(param))

    fun setDuration(seconds: Float) = intents.tryEmit(TweakTimelineIntent.SetDuration(seconds))
    fun setPlaybackMode(mode: TweakPlaybackMode) = intents.tryEmit(TweakTimelineIntent.SetPlaybackMode(mode))
    fun setEnabled(enabled: Boolean) = intents.tryEmit(TweakTimelineIntent.SetEnabled(enabled))

    fun expand() = intents.tryEmit(TweakTimelineIntent.Expand)
    fun collapse() = intents.tryEmit(TweakTimelineIntent.Collapse)
    fun save() = intents.tryEmit(TweakTimelineIntent.Save)
    fun cancel() = intents.tryEmit(TweakTimelineIntent.Cancel)

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}
