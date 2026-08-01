package org.balch.orpheus.features.looper

import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey

@Immutable
data class LooperUiState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val position: Float = 0f,
    val loopDuration: Double = 0.0,
    val quantize: Boolean = false,
    val level: Float = 0.7f
)

@Immutable
data class LooperActions(
    val setRecord: (Boolean) -> Unit,
    val setPlay: (Boolean) -> Unit,
    val clear: () -> Unit,
    val setQuantize: (Boolean) -> Unit,
    val setLevel: (Float) -> Unit
) {
    companion object {
        val EMPTY = LooperActions(
            setRecord = {},
            setPlay = {},
            clear = {},
            setQuantize = {},
            setLevel = {}
        )
    }
}

private sealed interface LooperIntent {
    data class Record(val recording: Boolean) : LooperIntent
    data class Play(val playing: Boolean) : LooperIntent
    object Clear : LooperIntent
    data class Quantize(val enabled: Boolean) : LooperIntent
    data class Level(val value: Float) : LooperIntent
    object Tick : LooperIntent
    data class UpdateProgress(val position: Float, val duration: Double) : LooperIntent
}

interface LooperFeature : SynthFeature<LooperUiState, LooperActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(LooperFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<LooperFeature>())
class LooperViewModel(
    private val synth: SynthEngine,
    scope: FeatureCoroutineScope,
) : LooperFeature {

    private val log = logging("LooperViewModel")
    private val _userIntents = MutableSharedFlow<LooperIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val actions = LooperActions(
        setRecord = ::setRecording,
        setPlay = ::setPlaying,
        clear = ::clearLoop,
        setQuantize = ::setQuantize,
        setLevel = ::setLevel
    )

    private val heartbeat = flow {
        while (currentCoroutineContext().isActive) {
            delay(33) // ~30fps
            emit(LooperIntent.Tick)
        }
    }

    override val stateFlow: StateFlow<LooperUiState> =
        merge(_userIntents, heartbeat)
            .scan(LooperUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = LooperUiState()
            )

    private fun reduce(state: LooperUiState, intent: LooperIntent): LooperUiState = when (intent) {
        is LooperIntent.Record -> {
            if (intent.recording) {
                state.copy(isRecording = true, isPlaying = false)
            } else {
                // Recording stopped -> synth auto-starts playback
                state.copy(isRecording = false, isPlaying = true)
            }
        }
        is LooperIntent.Play -> {
            // If we are currently recording and user hits Play, 
            // treat it as "Stop Recording" (which auto-triggers Play)
            if (intent.playing && state.isRecording) {
                state.copy(isRecording = false, isPlaying = true)
            } else {
                state.copy(isPlaying = intent.playing)
            }
        }
        is LooperIntent.Clear -> LooperUiState(quantize = state.quantize, level = state.level)
        is LooperIntent.Quantize -> state.copy(quantize = intent.enabled)
        is LooperIntent.Level -> state.copy(level = intent.value)
        is LooperIntent.Tick -> {
            if (state.isPlaying || state.isRecording) {
                state.copy(position = synth.getLooperPosition(), loopDuration = synth.getLooperDuration())
            } else {
                val dur = synth.getLooperDuration()
                if (dur != state.loopDuration) {
                    state.copy(loopDuration = dur)
                } else {
                    state
                }
            }
        }
        is LooperIntent.UpdateProgress -> state.copy(position = intent.position, loopDuration = intent.duration)
    }

    private fun applyToEngine(state: LooperUiState, intent: LooperIntent) {
        when (intent) {
            is LooperIntent.Record -> {
                log.debug { "setRecording: ${intent.recording}" }
                synth.setLooperRecord(intent.recording)
            }
            is LooperIntent.Play -> {
                log.debug { "setPlaying: ${intent.playing}" }
                if (intent.playing && state.isRecording) {
                    synth.setLooperRecord(false)
                } else {
                    synth.setLooperPlay(intent.playing)
                }
            }
            is LooperIntent.Clear -> {
                log.debug { "clearLoop" }
                synth.clearLooper()
            }
            is LooperIntent.Quantize -> {
                log.debug { "setQuantize: ${intent.enabled}" }
                synth.setLooperQuantize(intent.enabled)
            }
            is LooperIntent.Level -> {
                synth.setLooperLevel(intent.value)
            }
            is LooperIntent.Tick -> {}
            is LooperIntent.UpdateProgress -> {}
        }
    }

    private fun setRecording(recording: Boolean) {
        _userIntents.tryEmit(LooperIntent.Record(recording))
    }

    private fun setPlaying(playing: Boolean) {
        _userIntents.tryEmit(LooperIntent.Play(playing))
    }

    private fun clearLoop() {
        _userIntents.tryEmit(LooperIntent.Clear)
    }

    private fun setQuantize(enabled: Boolean) {
        _userIntents.tryEmit(LooperIntent.Quantize(enabled))
    }

    private fun setLevel(value: Float) {
        _userIntents.tryEmit(LooperIntent.Level(value))
    }

    companion object {
        fun previewFeature(state: LooperUiState = LooperUiState()): LooperFeature =
            object : LooperFeature {
                override val stateFlow: StateFlow<LooperUiState> = MutableStateFlow(state)
                override val actions: LooperActions = LooperActions.EMPTY
            }
    }
}
