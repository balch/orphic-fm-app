package org.balch.orpheus.features.distortion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.distortionMix
import org.balch.orpheus.core.presets.drive
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

@Immutable
data class DistortionUiState(
    val drive: Float = 0.0f,
    val volume: Float = 0.7f,
    val mix: Float = 0.5f,
    val peak: Float = 0.0f,
    val mode: StereoMode = StereoMode.VOICE_PAN,
    val masterPan: Float = 0f,  // -1=Left, 0=Center, 1=Right
    val voicePans: List<Float> = listOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f)
)

@Immutable
data class DistortionPanelActions(
    val setDrive: (Float) -> Unit,
    val setVolume: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setMode: (StereoMode) -> Unit,
    val setMasterPan: (Float) -> Unit,
) {
    companion object {
        val EMPTY = DistortionPanelActions({}, {}, {}, {}, {})
    }
}

/** User intents for the Distortion panel. */
private sealed interface DistortionIntent {
    data class Drive(val value: Float, val fromSequencer: Boolean = false) : DistortionIntent
    data class Volume(val value: Float) : DistortionIntent
    data class Mix(val value: Float, val fromSequencer: Boolean = false) : DistortionIntent
    data class Peak(val value: Float) : DistortionIntent

    data class SetMode(val mode: StereoMode) : DistortionIntent
    data class SetMasterPan(val pan: Float) : DistortionIntent
    data class SetVoicePan(val index: Int, val pan: Float) : DistortionIntent

    data class Restore(val state: DistortionUiState) : DistortionIntent
}

typealias DistortionFeature = SynthFeature<DistortionUiState, DistortionPanelActions>

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
    private val synthController: SynthController,
    presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DistortionFeature {

    override val actions = DistortionPanelActions(
        setDrive = ::setDrive,
        setVolume = ::setVolume,
        setMix = ::setMix,
        setMode = ::setMode,
        setMasterPan = ::setMasterPan
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<DistortionIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> DistortionIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        DistortionIntent.Restore(
            DistortionUiState(
                drive = preset.drive,
                volume = engine.getMasterVolume(),
                mix = preset.distortionMix,
                peak = 0.0f,
                mode = engine.getStereoMode(),
                masterPan = engine.getMasterPan(),
                voicePans = List(8) { engine.getVoicePan(it) }
            )
        )
    }

    // Peak level updates from engine
    private val peakIntents = engine.peakFlow.map { peak ->
        DistortionIntent.Peak(peak)
    }

    // Control changes -> DistortionIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.MASTER_VOLUME -> DistortionIntent.Volume(event.value)
            ControlIds.DRIVE -> DistortionIntent.Drive(event.value, fromSequencer)
            ControlIds.DISTORTION_MIX -> DistortionIntent.Mix(event.value, fromSequencer)
            ControlIds.STEREO_MODE -> DistortionIntent.SetMode(
                if (event.value >= 0.5f) StereoMode.STEREO_DELAYS else StereoMode.VOICE_PAN
            )
            // Convert 0-1 to -1..1 for pan
            ControlIds.STEREO_PAN -> DistortionIntent.SetMasterPan((event.value * 2f) - 1f)
            else -> null
        }
    }

    override val stateFlow: StateFlow<DistortionUiState> =
        merge(_userIntents, presetIntents, peakIntents, controlIntents)
            .scan(DistortionUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DistortionUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DistortionUiState, intent: DistortionIntent): DistortionUiState =
        when (intent) {
            is DistortionIntent.Drive -> state.copy(drive = intent.value)
            is DistortionIntent.Volume -> state.copy(volume = intent.value)
            is DistortionIntent.Mix -> state.copy(mix = intent.value)
            is DistortionIntent.Peak -> state.copy(peak = intent.value)
            is DistortionIntent.SetMode -> state.copy(mode = intent.mode)
            is DistortionIntent.SetMasterPan -> state.copy(masterPan = intent.pan)
            is DistortionIntent.SetVoicePan -> {
                val newPans = state.voicePans.toMutableList()
                newPans[intent.index] = intent.pan
                state.copy(voicePans = newPans)
            }

            is DistortionIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: DistortionUiState, intent: DistortionIntent) {
        when (intent) {
            // Skip engine calls for SEQUENCER events - engine is driven by audio-rate automation
            is DistortionIntent.Drive -> if (!intent.fromSequencer) engine.setDrive(intent.value)
            is DistortionIntent.Volume -> engine.setMasterVolume(intent.value)
            is DistortionIntent.Mix -> if (!intent.fromSequencer) engine.setDistortionMix(intent.value)
            is DistortionIntent.Peak -> { /* Peak is read-only from engine */ }
            is DistortionIntent.SetMode -> engine.setStereoMode(intent.mode)
            is DistortionIntent.SetMasterPan -> engine.setMasterPan(intent.pan)
            is DistortionIntent.SetVoicePan -> engine.setVoicePan(intent.index, intent.pan)
            is DistortionIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: DistortionUiState) {
        engine.setDrive(state.drive)
        engine.setMasterVolume(state.volume)
        engine.setDistortionMix(state.mix)
        engine.setStereoMode(state.mode)
        engine.setMasterPan(state.masterPan)
        state.voicePans.forEachIndexed { index, pan ->
            engine.setVoicePan(index, pan)
        }

    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setDrive(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DistortionIntent.Drive(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DRIVE, value, ControlEventOrigin.UI)
    }

    fun setVolume(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Volume(value))
        synthController.emitControlChange(ControlIds.MASTER_VOLUME, value, ControlEventOrigin.UI)
    }

    fun setMix(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(DistortionIntent.Mix(value, fromSequencer))
        synthController.emitControlChange(ControlIds.DISTORTION_MIX, value, ControlEventOrigin.UI)
    }

    fun setMode(mode: StereoMode) {
        _userIntents.tryEmit(DistortionIntent.SetMode(mode))
        val value = if (mode == StereoMode.STEREO_DELAYS) 1f else 0f
        synthController.emitControlChange(ControlIds.STEREO_MODE, value, ControlEventOrigin.UI)
    }

    fun setMasterPan(pan: Float) {
        _userIntents.tryEmit(DistortionIntent.SetMasterPan(pan))
        // Convert -1..1 to 0..1 for control event
        val value = (pan + 1f) / 2f
        synthController.emitControlChange(ControlIds.STEREO_PAN, value, ControlEventOrigin.UI)
    }

    fun setVoicePan(index: Int, pan: Float) {
        _userIntents.tryEmit(DistortionIntent.SetVoicePan(index, pan))
    }

    fun updatePeak(value: Float) {
        _userIntents.tryEmit(DistortionIntent.Peak(value))
    }

    fun restoreState(state: DistortionUiState) {
        _userIntents.tryEmit(DistortionIntent.Restore(state))
    }

    companion object {
        fun previewFeature(state: DistortionUiState = DistortionUiState()): DistortionFeature =
            object : DistortionFeature {
                override val stateFlow: StateFlow<DistortionUiState> = MutableStateFlow(state)
                override val actions: DistortionPanelActions = DistortionPanelActions.EMPTY
            }

        @Composable
        fun feature(): DistortionFeature =
            synthViewModel<DistortionViewModel, DistortionFeature>()
    }
}
