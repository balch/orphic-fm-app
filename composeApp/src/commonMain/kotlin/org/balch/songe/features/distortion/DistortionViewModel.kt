package org.balch.songe.features.distortion

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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.presets.PresetLoader

/** UI state for the Distortion/Volume panel. */
data class DistortionUiState(
    val drive: Float = 0.0f,
    val volume: Float = 0.7f,
    val mix: Float = 0.5f,
    val peak: Float = 0.0f
)

/** User intents for the Distortion panel. */
private sealed interface DistortionIntent {
    data class Drive(val value: Float) : DistortionIntent
    data class Volume(val value: Float) : DistortionIntent
    data class Mix(val value: Float) : DistortionIntent
    data class Peak(val value: Float) : DistortionIntent
    data class Restore(val state: DistortionUiState) : DistortionIntent
}

/**
 * ViewModel for the Distortion panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(DistortionViewModel::class)
@ContributesIntoMap(AppScope::class)
class DistortionViewModel(
    private val engine: SongeEngine,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<DistortionIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<DistortionUiState> =
        intents
            .onEach { intent -> applyToEngine(intent) }
            .scan(DistortionUiState()) { state, intent -> reduce(state, intent) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DistortionUiState()
            )

    init {
        applyFullState(uiState.value)

        viewModelScope.launch {
            presetLoader.presetFlow.collect { preset ->
                val distortionState =
                    DistortionUiState(
                        drive = preset.drive,
                        volume = preset.masterVolume,
                        mix = preset.distortionMix
                    )
                intents.tryEmit(DistortionIntent.Restore(distortionState))
            }
        }

        viewModelScope.launch {
            engine.peakFlow.collect { peak ->
                intents.tryEmit(DistortionIntent.Peak(peak))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DistortionUiState, intent: DistortionIntent): DistortionUiState =
        when (intent) {
            is DistortionIntent.Drive ->
                state.copy(drive = intent.value)

            is DistortionIntent.Volume ->
                state.copy(volume = intent.value)

            is DistortionIntent.Mix ->
                state.copy(mix = intent.value)

            is DistortionIntent.Peak ->
                state.copy(peak = intent.value)

            is DistortionIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(intent: DistortionIntent) {
        when (intent) {
            is DistortionIntent.Drive -> engine.setDrive(intent.value)
            is DistortionIntent.Volume -> engine.setMasterVolume(intent.value)
            is DistortionIntent.Mix -> engine.setDistortionMix(intent.value)
            is DistortionIntent.Peak -> {
                /* Peak is read-only from engine */
            }

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
        intents.tryEmit(DistortionIntent.Drive(value))
    }

    fun onVolumeChange(value: Float) {
        intents.tryEmit(DistortionIntent.Volume(value))
    }

    fun onMixChange(value: Float) {
        intents.tryEmit(DistortionIntent.Mix(value))
    }

    fun updatePeak(value: Float) {
        intents.tryEmit(DistortionIntent.Peak(value))
    }

    fun restoreState(state: DistortionUiState) {
        intents.tryEmit(DistortionIntent.Restore(state))
    }
}
