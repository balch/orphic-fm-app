package org.balch.orpheus.features.looper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.synthViewModel

@Immutable
data class LooperUiState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val position: Float = 0f,
    val loopDuration: Double = 0.0
)

data class LooperActions(
    val setRecord: (Boolean) -> Unit,
    val setPlay: (Boolean) -> Unit,
    val clear: () -> Unit
) {
    companion object {
        val EMPTY = LooperActions(
            setRecord = {},
            setPlay = {},
            clear = {}
        )
    }
}

typealias LooperFeature = SynthFeature<LooperUiState, LooperActions>

@Inject
@ViewModelKey(LooperViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LooperViewModel(
    private val synth: SynthEngine
) : ViewModel(), LooperFeature {

    private val log = logging("LooperViewModel")
    private val _uiState = MutableStateFlow(LooperUiState())
    override val stateFlow: StateFlow<LooperUiState> = _uiState.asStateFlow()

    override val actions = LooperActions(
        setRecord = ::setRecording,
        setPlay = ::setPlaying,
        clear = ::clearLoop
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                val isRecording = _uiState.value.isRecording
                val isPlaying = _uiState.value.isPlaying
                
                if (isPlaying || isRecording) {
                    val pos = synth.getLooperPosition()
                    val dur = synth.getLooperDuration()
                    _uiState.update { it.copy(position = pos, loopDuration = dur) }
                } else {
                    val dur = synth.getLooperDuration()
                    if (dur != _uiState.value.loopDuration) {
                        _uiState.update { it.copy(loopDuration = dur) }
                    }
                }
                delay(33) // ~30fps
            }
        }
    }

    private fun setRecording(recording: Boolean) {
        log.debug { "setRecording: $recording" }
        synth.setLooperRecord(recording)
        
        // Update UI state - the synth auto-starts playback when recording stops
        _uiState.update { 
            if (recording) {
                it.copy(isRecording = true, isPlaying = false)
            } else {
                // Recording stopped -> synth auto-starts playback
                it.copy(isRecording = false, isPlaying = true)
            }
        }
    }

    private fun setPlaying(playing: Boolean) {
        log.debug { "setPlaying: $playing" }
        
        // If we are currently recording and user hits Play, 
        // treat it as "Stop Recording" (which auto-triggers Play)
        if (playing && _uiState.value.isRecording) {
            setRecording(false)
            return
        }

        synth.setLooperPlay(playing)
        _uiState.update { it.copy(isPlaying = playing) }
    }

    private fun clearLoop() {
        log.debug { "clearLoop" }
        synth.clearLooper()
        _uiState.update { LooperUiState() }
    }

    companion object {
        fun previewFeature(state: LooperUiState = LooperUiState()): LooperFeature =
            object : LooperFeature {
                override val stateFlow: StateFlow<LooperUiState> = MutableStateFlow(state)
                override val actions: LooperActions = LooperActions.EMPTY
            }

        @Composable
        fun feature(): LooperFeature =
            synthViewModel<LooperViewModel, LooperFeature>()
    }
}
