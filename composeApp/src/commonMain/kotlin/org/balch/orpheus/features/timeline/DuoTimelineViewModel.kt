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
 * State for a single timeline instance.
 */
data class TimelineInstanceState(
    val config: DuoTimelineConfig = DuoTimelineConfig(),
    val pathA: TimelinePath = TimelinePath(),
    val pathB: TimelinePath = TimelinePath(),
    val currentPosition: Float = 0f,
    val isPlaying: Boolean = false,
    val playDirection: Int = 1  // 1 = forward, -1 = backward (for ping-pong)
)

/**
 * UI state for the DuoTimeline feature.
 */
data class DuoTimelineUiState(
    val timelines: Map<TimelineTarget, TimelineInstanceState> = mapOf(
        TimelineTarget.LFO to TimelineInstanceState(config = DuoTimelineConfig.LFO),
        TimelineTarget.DELAY to TimelineInstanceState(config = DuoTimelineConfig.DELAY)
    ),
    val expandedTarget: TimelineTarget? = null,
    val selectedParam: Int = 0  // 0 = A, 1 = B
)

/**
 * User intents for the DuoTimeline feature.
 */
sealed interface TimelineIntent {
    // Playback
    data class Play(val target: TimelineTarget) : TimelineIntent
    data class Pause(val target: TimelineTarget) : TimelineIntent
    data class Stop(val target: TimelineTarget) : TimelineIntent
    data class UpdatePosition(val target: TimelineTarget, val position: Float, val direction: Int) : TimelineIntent

    // Drawing
    data class StartPath(val target: TimelineTarget, val param: Int, val point: TimelinePoint) : TimelineIntent
    data class AddPoint(val target: TimelineTarget, val param: Int, val point: TimelinePoint) : TimelineIntent
    data class RemovePointsAfter(val target: TimelineTarget, val param: Int, val time: Float) : TimelineIntent
    data class ClearPath(val target: TimelineTarget, val param: Int) : TimelineIntent
    data class CompletePath(val target: TimelineTarget, val param: Int, val endValue: Float) : TimelineIntent

    // Configuration
    data class SetDuration(val target: TimelineTarget, val seconds: Float) : TimelineIntent
    data class SetPlaybackMode(val target: TimelineTarget, val mode: PlaybackMode) : TimelineIntent
    data class SetEnabled(val target: TimelineTarget, val enabled: Boolean) : TimelineIntent

    // UI
    data class ExpandTimeline(val target: TimelineTarget) : TimelineIntent
    data object CollapseTimeline : TimelineIntent
    data class SelectParam(val index: Int) : TimelineIntent
    data class Save(val target: TimelineTarget) : TimelineIntent
    data class Cancel(val target: TimelineTarget) : TimelineIntent
}

/**
 * ViewModel for the DuoTimeline feature.
 *
 * Manages multiple timeline instances (LFO, Delay, etc.) and handles playback,
 * drawing, and configuration. Uses MVI pattern with intents flowing through
 * a reducer to produce state.
 */
@Inject
@ViewModelKey(DuoTimelineViewModel::class)
@ContributesIntoMap(AppScope::class)
class DuoTimelineViewModel(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents = MutableSharedFlow<TimelineIntent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiState: StateFlow<DuoTimelineUiState> =
        intents
            .onEach { intent -> applySideEffects(intent) }
            .scan(DuoTimelineUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DuoTimelineUiState()
            )

    // Playback jobs for each timeline
    private val playbackJobs = mutableMapOf<TimelineTarget, Job>()

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DuoTimelineUiState, intent: TimelineIntent): DuoTimelineUiState {
        return when (intent) {
            is TimelineIntent.Play -> state.updateTimeline(intent.target) {
                it.copy(isPlaying = true)
            }

            is TimelineIntent.Pause -> state.updateTimeline(intent.target) {
                it.copy(isPlaying = false)
            }

            is TimelineIntent.Stop -> state.updateTimeline(intent.target) {
                it.copy(isPlaying = false, currentPosition = 0f, playDirection = 1)
            }

            is TimelineIntent.UpdatePosition -> state.updateTimeline(intent.target) {
                it.copy(currentPosition = intent.position, playDirection = intent.direction)
            }

            is TimelineIntent.StartPath -> state.updateTimeline(intent.target) {
                val path = if (intent.param == 0) it.pathA else it.pathB
                val newPath = path.startedAt(intent.point)
                if (intent.param == 0) it.copy(pathA = newPath) else it.copy(pathB = newPath)
            }

            is TimelineIntent.AddPoint -> state.updateTimeline(intent.target) {
                val path = if (intent.param == 0) it.pathA else it.pathB
                val newPath = path.withPointAdded(intent.point)
                if (intent.param == 0) it.copy(pathA = newPath) else it.copy(pathB = newPath)
            }

            is TimelineIntent.RemovePointsAfter -> state.updateTimeline(intent.target) {
                val path = if (intent.param == 0) it.pathA else it.pathB
                val newPath = path.withPointsRemovedAfter(intent.time)
                if (intent.param == 0) it.copy(pathA = newPath) else it.copy(pathB = newPath)
            }

            is TimelineIntent.ClearPath -> state.updateTimeline(intent.target) {
                if (intent.param == 0) it.copy(pathA = TimelinePath())
                else it.copy(pathB = TimelinePath())
            }

            is TimelineIntent.CompletePath -> state.updateTimeline(intent.target) {
                val path = if (intent.param == 0) it.pathA else it.pathB
                val newPath = path.completed(intent.endValue)
                if (intent.param == 0) it.copy(pathA = newPath) else it.copy(pathB = newPath)
            }

            is TimelineIntent.SetDuration -> state.updateTimeline(intent.target) {
                it.copy(config = it.config.copy(durationSeconds = intent.seconds.coerceIn(
                    DuoTimelineConfig.MIN_DURATION,
                    DuoTimelineConfig.MAX_DURATION
                )))
            }

            is TimelineIntent.SetPlaybackMode -> state.updateTimeline(intent.target) {
                it.copy(config = it.config.copy(playbackMode = intent.mode))
            }

            is TimelineIntent.SetEnabled -> state.updateTimeline(intent.target) {
                it.copy(config = it.config.copy(enabled = intent.enabled))
            }

            is TimelineIntent.ExpandTimeline -> state.copy(expandedTarget = intent.target)

            is TimelineIntent.CollapseTimeline -> state.copy(expandedTarget = null)

            is TimelineIntent.SelectParam -> state.copy(selectedParam = intent.index)

            is TimelineIntent.Save -> state.copy(expandedTarget = null)

            is TimelineIntent.Cancel -> state.copy(expandedTarget = null)
        }
    }

    private fun DuoTimelineUiState.updateTimeline(
        target: TimelineTarget,
        update: (TimelineInstanceState) -> TimelineInstanceState
    ): DuoTimelineUiState {
        val current = timelines[target] ?: TimelineInstanceState()
        return copy(timelines = timelines + (target to update(current)))
    }

    // ═══════════════════════════════════════════════════════════
    // SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applySideEffects(intent: TimelineIntent) {
        when (intent) {
            is TimelineIntent.Play -> startPlayback(intent.target)
            is TimelineIntent.Pause -> stopPlayback(intent.target)
            is TimelineIntent.Stop -> stopPlayback(intent.target)
            is TimelineIntent.UpdatePosition -> applyParameterValues(intent.target)
            else -> { /* No side effects */ }
        }
    }

    private fun startPlayback(target: TimelineTarget) {
        // Cancel existing playback job
        playbackJobs[target]?.cancel()

        playbackJobs[target] = viewModelScope.launch(dispatcherProvider.io) {
            val tickMs = 16L  // ~60fps
            var lastTime = currentTimeMillis()

            while (isActive) {
                val currentState = uiState.value.timelines[target] ?: break
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

                // Handle end conditions based on playback mode
                when (currentState.config.playbackMode) {
                    PlaybackMode.ONCE -> {
                        if (newPosition >= 1f) {
                            newPosition = 1f
                            intents.tryEmit(TimelineIntent.Stop(target))
                        }
                    }

                    PlaybackMode.LOOP -> {
                        if (newPosition >= 1f) {
                            newPosition = 0f
                        }
                    }

                    PlaybackMode.PING_PONG -> {
                        if (newPosition >= 1f) {
                            newPosition = 1f
                            newDirection = -1
                        } else if (newPosition <= 0f) {
                            newPosition = 0f
                            newDirection = 1
                        }
                    }
                }

                intents.tryEmit(TimelineIntent.UpdatePosition(target, newPosition.coerceIn(0f, 1f), newDirection))
                delay(tickMs)
            }
        }
    }

    private fun stopPlayback(target: TimelineTarget) {
        playbackJobs[target]?.cancel()
        playbackJobs.remove(target)
    }

    private fun applyParameterValues(target: TimelineTarget) {
        val state = uiState.value.timelines[target] ?: return
        if (!state.config.enabled) return

        val valueA = state.pathA.valueAt(state.currentPosition)
        val valueB = state.pathB.valueAt(state.currentPosition)

        when (target) {
            TimelineTarget.LFO -> {
                valueA?.let { engine.setHyperLfoFreq(0, it) }
                valueB?.let { engine.setHyperLfoFreq(1, it) }
            }

            TimelineTarget.DELAY -> {
                valueA?.let { engine.setDelayTime(0, it) }
                valueB?.let { engine.setDelayTime(1, it) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun play(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.Play(target))
    }

    fun pause(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.Pause(target))
    }

    fun stop(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.Stop(target))
    }

    fun startPath(target: TimelineTarget, param: Int, point: TimelinePoint) {
        intents.tryEmit(TimelineIntent.StartPath(target, param, point))
    }

    fun addPoint(target: TimelineTarget, param: Int, point: TimelinePoint) {
        intents.tryEmit(TimelineIntent.AddPoint(target, param, point))
    }

    fun removePointsAfter(target: TimelineTarget, param: Int, time: Float) {
        intents.tryEmit(TimelineIntent.RemovePointsAfter(target, param, time))
    }

    fun clearPath(target: TimelineTarget, param: Int) {
        intents.tryEmit(TimelineIntent.ClearPath(target, param))
    }

    fun completePath(target: TimelineTarget, param: Int, endValue: Float) {
        intents.tryEmit(TimelineIntent.CompletePath(target, param, endValue))
    }

    fun setDuration(target: TimelineTarget, seconds: Float) {
        intents.tryEmit(TimelineIntent.SetDuration(target, seconds))
    }

    fun setPlaybackMode(target: TimelineTarget, mode: PlaybackMode) {
        intents.tryEmit(TimelineIntent.SetPlaybackMode(target, mode))
    }

    fun setEnabled(target: TimelineTarget, enabled: Boolean) {
        intents.tryEmit(TimelineIntent.SetEnabled(target, enabled))
    }

    fun expandTimeline(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.ExpandTimeline(target))
    }

    fun collapseTimeline() {
        intents.tryEmit(TimelineIntent.CollapseTimeline)
    }

    fun selectParam(index: Int) {
        intents.tryEmit(TimelineIntent.SelectParam(index))
    }

    fun save(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.Save(target))
    }

    fun cancel(target: TimelineTarget) {
        intents.tryEmit(TimelineIntent.Cancel(target))
    }

    override fun onCleared() {
        super.onCleared()
        playbackJobs.values.forEach { it.cancel() }
        playbackJobs.clear()
    }
}
