package org.balch.orpheus.features.timer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager

enum class TimerStatus { IDLE, RUNNING, PAUSED, FADING, FINISHED }

@Immutable
data class TimerUiState(
    val durationMinutes: Int = 30,
    val remainingSeconds: Long = 1800,
    val status: TimerStatus = TimerStatus.IDLE,
    val fadeProgress: Float = 1.0f,
    val showOverlay: Boolean = false,
    val overlayPosition: Pair<Float, Float> = 0f to 0f,
    val overlaySize: Pair<Float, Float> = 420f to 350f,
)

@Immutable
data class TimerActions(
    val onSetDuration: (Int) -> Unit,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onStop: () -> Unit,
    val onStopAll: () -> Unit,
    val onReset: () -> Unit,
    val onToggleOverlay: () -> Unit,
    val onOverlayPositionChange: (Float, Float) -> Unit,
    val onOverlaySizeChange: (Float, Float) -> Unit,
) {
    companion object {
        val EMPTY = TimerActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })
    }
}

interface TimerFeature : SynthFeature<TimerUiState, TimerActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.TIMER
            override val title = "Sleep Timer"
            override val markdown = """
                Sleep timer that fades audio out after a set duration.

                ## Controls
                - **Duration**: Set timer duration in minutes (0–260).
                - **Start**: Begin countdown.
                - **Pause**: Pause the countdown.
                - **Stop**: Stop the timer (audio continues).
                - **Stop All**: Stop the timer and all audio playback.
                - **Reset**: Reset to original duration.
            """.trimIndent()
            override val portControlKeys = emptyMap<String, String>()
        }
    }
}

@Inject
@ClassKey(TimerViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class TimerViewModel(
    private val synthEngine: SynthEngine,
    private val playbackLifecycleManager: PlaybackLifecycleManager,
    private val metadataNotifier: TimerMetadataNotifier,
    private val widgetNotifier: TimerWidgetNotifier,
    scope: FeatureCoroutineScope,
) : TimerFeature {

    private val coroutineScope = scope

    private val _state = MutableStateFlow(TimerUiState())
    override val stateFlow: StateFlow<TimerUiState> = _state.asStateFlow()

    private val wakeLockManager = WakeLockManager()

    private var countdownJob: Job? = null
    private var fadeJob: Job? = null
    private var savedVolume: Float = 1.0f

    init {
        coroutineScope.launch {
            TimerWidgetCommandBus.commands.collect { command ->
                when (command) {
                    TimerWidgetCommand.PLAY_PAUSE -> {
                        if (_state.value.status == TimerStatus.RUNNING) pause() else start()
                    }
                    TimerWidgetCommand.STOP -> stop()
                }
            }
        }
    }

    override val actions = TimerActions(
        onSetDuration = ::setDuration,
        onStart = ::start,
        onPause = ::pause,
        onStop = ::stop,
        onStopAll = ::stopAll,
        onReset = ::reset,
        onToggleOverlay = ::toggleOverlay,
        onOverlayPositionChange = ::updateOverlayPosition,
        onOverlaySizeChange = ::updateOverlaySize,
    )

    private fun setDuration(minutes: Int) {
        val clamped = minutes.coerceIn(0, 260)
        _state.update { it.copy(durationMinutes = clamped, remainingSeconds = clamped * 60L) }
    }

    private fun start() {
        val currentStatus = _state.value.status
        if (currentStatus == TimerStatus.RUNNING) return

        if (_state.value.remainingSeconds <= 0 && currentStatus != TimerStatus.PAUSED) return

        // Resume from pause: just restart the countdown job, don't re-capture volume or wake lock
        if (currentStatus == TimerStatus.PAUSED) {
            _state.update { it.copy(status = TimerStatus.RUNNING) }
            notifyWidget(TimerStatus.RUNNING)
            launchCountdownJob()
            return
        }

        savedVolume = synthEngine.getMasterVolume()
        wakeLockManager.acquire()
        metadataNotifier.setTimerActive(true)

        _state.update { it.copy(status = TimerStatus.RUNNING, showOverlay = true) }
        updateTimerMetadata(_state.value.remainingSeconds)
        notifyWidget(TimerStatus.RUNNING)

        launchCountdownJob()
    }

    private fun launchCountdownJob() {
        countdownJob = coroutineScope.launch {
            while (true) {
                delay(1_000L)
                val remaining = _state.value.remainingSeconds - 1
                if (remaining <= 0) {
                    _state.update { it.copy(remainingSeconds = 0, status = TimerStatus.FADING) }
                    notifyWidget(TimerStatus.FADING)
                    countdownJob = null
                    startFade()
                    break
                } else {
                    val prevMinute = _state.value.remainingSeconds / 60
                    _state.update { it.copy(remainingSeconds = remaining) }
                    if (remaining / 60 != prevMinute) {
                        updateTimerMetadata(remaining)
                    }
                }
            }
        }
    }

    private fun updateTimerMetadata(remainingSeconds: Long) {
        val timeStr = formatTime(remainingSeconds)
        metadataNotifier.updateTimerTitle("Sleep Timer: $timeStr remaining")
    }

    private fun notifyWidget(status: TimerStatus) {
        val state = _state.value
        widgetNotifier.notifyStateChanged(
            remainingSeconds = state.remainingSeconds,
            running = status == TimerStatus.RUNNING,
            statusText = status.name,
        )
    }

    private fun startFade() {
        fadeJob = coroutineScope.launch {
            val fadeDurationMs = 15_000L
            val stepMs = 100L
            val steps = fadeDurationMs / stepMs
            for (i in 0..steps) {
                val progress = 1.0f - (i.toFloat() / steps.toFloat())
                _state.update { it.copy(fadeProgress = progress) }
                synthEngine.setMasterVolume(savedVolume * progress)
                if (i < steps) delay(stepMs)
            }
            // Fade complete — stop agents before restoring volume to avoid
            // an audible pop (volume briefly at full while agents still produce audio)
            wakeLockManager.release()
            playbackLifecycleManager.tryRequestStopAll()
            metadataNotifier.setTimerActive(false)
            delay(100) // allow agents to stop producing audio
            synthEngine.setMasterVolume(savedVolume)
            val durationMinutes = _state.value.durationMinutes
            _state.update {
                it.copy(
                    status = TimerStatus.FINISHED,
                    fadeProgress = 1.0f,
                    remainingSeconds = durationMinutes * 60L,
                    showOverlay = false,
                )
            }
            notifyWidget(TimerStatus.FINISHED)
        }
    }

    private fun pause() {
        if (_state.value.status != TimerStatus.RUNNING) return
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(status = TimerStatus.PAUSED) }
        notifyWidget(TimerStatus.PAUSED)
    }

    private fun stop() {
        cancelAllJobs()
        restoreVolumeIfFading()
        _state.update { it.copy(status = TimerStatus.IDLE, showOverlay = false) }
        notifyWidget(TimerStatus.IDLE)
    }

    private fun stopAll() {
        stop()
        playbackLifecycleManager.tryRequestStopAll()
    }

    private fun reset() {
        cancelAllJobs()
        restoreVolumeIfFading()
        val durationMinutes = _state.value.durationMinutes
        _state.update {
            it.copy(
                status = TimerStatus.IDLE,
                remainingSeconds = durationMinutes * 60L,
                fadeProgress = 1.0f,
            )
        }
        notifyWidget(TimerStatus.IDLE)
    }

    private fun toggleOverlay() {
        _state.update { it.copy(showOverlay = !it.showOverlay) }
    }

    private fun updateOverlayPosition(x: Float, y: Float) {
        _state.update { it.copy(overlayPosition = x to y) }
    }

    private fun updateOverlaySize(width: Float, height: Float) {
        _state.update { it.copy(overlaySize = width to height) }
    }

    private fun cancelAllJobs() {
        countdownJob?.cancel()
        countdownJob = null
        fadeJob?.cancel()
        fadeJob = null
        wakeLockManager.release()
        metadataNotifier.setTimerActive(false)
    }

    private fun restoreVolumeIfFading() {
        if (_state.value.status == TimerStatus.FADING) {
            synthEngine.setMasterVolume(savedVolume)
            _state.update { it.copy(fadeProgress = 1.0f) }
        }
    }

    companion object {
        fun previewFeature(state: TimerUiState = TimerUiState()): TimerFeature =
            object : TimerFeature {
                override val stateFlow: StateFlow<TimerUiState> = MutableStateFlow(state)
                override val actions: TimerActions = TimerActions.EMPTY
            }

        @Composable
        fun feature(): TimerFeature = synthFeature<TimerViewModel, TimerFeature>()
    }
}
