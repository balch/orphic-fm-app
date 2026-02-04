package org.balch.orpheus.features.resonator

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
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.resonatorBrightness
import org.balch.orpheus.core.presets.resonatorDamping
import org.balch.orpheus.core.presets.resonatorMix
import org.balch.orpheus.core.presets.resonatorMode
import org.balch.orpheus.core.presets.resonatorPosition
import org.balch.orpheus.core.presets.resonatorSnapBack
import org.balch.orpheus.core.presets.resonatorStructure
import org.balch.orpheus.core.presets.resonatorTargetMix
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

enum class ResonatorMode(val displayName: String) {
    MODAL("Bar"),
    STRING("String"),
    SYMPATHETIC("Sitar")
}

/**
 * UI state for the Rings Resonator.
 */
@Immutable
data class ResonatorUiState(
    val mode: ResonatorMode = ResonatorMode.MODAL,
    val targetMix: Float = 0.5f,     // 0=Drums only, 0.5=Both, 1=Synth only
    val snapBack: Boolean = false,   // Whether fader snaps back to center on release
    val structure: Float = 0.25f,    // Material/inharmonicity (0-1)
    val brightness: Float = 0.5f,    // High freq content (0-1)
    val damping: Float = 0.3f,       // Decay time (0-1)
    val position: Float = 0.5f,      // Excitation point (0-1)
    val mix: Float = 0f              // Dry/wet (0-1)
)

/**
 * Actions for controlling the resonator.
 */
@Immutable
data class ResonatorPanelActions(
    val setMode: (ResonatorMode) -> Unit,
    val setTargetMix: (Float) -> Unit,
    val setSnapBack: (Boolean) -> Unit,
    val setStructure: (Float) -> Unit,
    val setBrightness: (Float) -> Unit,
    val setDamping: (Float) -> Unit,
    val setPosition: (Float) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object {
        val EMPTY = ResonatorPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Resonator panel. */
private sealed interface ResonatorIntent {
    data class SetMode(val mode: ResonatorMode) : ResonatorIntent
    data class Structure(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class Brightness(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class Damping(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class Position(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class Mix(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class TargetMix(val value: Float, val fromSequencer: Boolean = false) : ResonatorIntent
    data class SnapBack(val active: Boolean, val fromSequencer: Boolean = false) : ResonatorIntent
    data class Restore(val state: ResonatorUiState) : ResonatorIntent
}

typealias ResonatorFeature = SynthFeature<ResonatorUiState, ResonatorPanelActions>

@Inject
@ViewModelKey(ResonatorViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ResonatorViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider,
) : ViewModel(), ResonatorFeature {

    override val actions = ResonatorPanelActions(
        setMode = ::setMode,
        setTargetMix = ::setTargetMix,
        setSnapBack = ::setSnapBack,
        setStructure = ::setStructure,
        setBrightness = ::setBrightness,
        setDamping = ::setDamping,
        setPosition = ::setPosition,
        setMix = ::setMix
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<ResonatorIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> ResonatorIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        ResonatorIntent.Restore(
            ResonatorUiState(
                mode = ResonatorMode.entries.getOrElse(preset.resonatorMode) { ResonatorMode.MODAL },
                targetMix = preset.resonatorTargetMix,
                snapBack = preset.resonatorSnapBack,
                structure = preset.resonatorStructure,
                brightness = preset.resonatorBrightness,
                damping = preset.resonatorDamping,
                position = preset.resonatorPosition,
                mix = preset.resonatorMix
            )
        )
    }

    // Control changes -> ResonatorIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.RESONATOR_MODE -> {
                val mode = when {
                    event.value < 0.33f -> ResonatorMode.MODAL
                    event.value < 0.66f -> ResonatorMode.STRING
                    else -> ResonatorMode.SYMPATHETIC
                }
                ResonatorIntent.SetMode(mode)
            }
            ControlIds.RESONATOR_STRUCTURE -> ResonatorIntent.Structure(event.value, fromSequencer)
            ControlIds.RESONATOR_BRIGHTNESS -> ResonatorIntent.Brightness(event.value, fromSequencer)
            ControlIds.RESONATOR_DAMPING -> ResonatorIntent.Damping(event.value, fromSequencer)
            ControlIds.RESONATOR_POSITION -> ResonatorIntent.Position(event.value, fromSequencer)
            ControlIds.RESONATOR_MIX -> ResonatorIntent.Mix(event.value, fromSequencer)
            ControlIds.RESONATOR_TARGET_MIX -> ResonatorIntent.TargetMix(event.value, fromSequencer)
            ControlIds.RESONATOR_SNAP_BACK -> ResonatorIntent.SnapBack(event.value > 0.5f, fromSequencer)
            else -> null
        }
    }

    override val stateFlow: StateFlow<ResonatorUiState> =
        merge(_userIntents, presetIntents, controlIntents)
            .scan(ResonatorUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ResonatorUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: ResonatorUiState, intent: ResonatorIntent): ResonatorUiState =
        when (intent) {
            is ResonatorIntent.SetMode -> state.copy(mode = intent.mode)
            is ResonatorIntent.Structure -> state.copy(structure = intent.value)
            is ResonatorIntent.Brightness -> state.copy(brightness = intent.value)
            is ResonatorIntent.Damping -> state.copy(damping = intent.value)
            is ResonatorIntent.Position -> state.copy(position = intent.value)
            is ResonatorIntent.Mix -> state.copy(mix = intent.value)
            is ResonatorIntent.TargetMix -> state.copy(targetMix = intent.value)
            is ResonatorIntent.SnapBack -> state.copy(snapBack = intent.active)
            is ResonatorIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: ResonatorUiState, intent: ResonatorIntent) {
        when (intent) {
            is ResonatorIntent.SetMode -> engine.setResonatorMode(intent.mode.ordinal)
            is ResonatorIntent.Structure -> if (!intent.fromSequencer) engine.setResonatorStructure(intent.value)
            is ResonatorIntent.Brightness -> if (!intent.fromSequencer) engine.setResonatorBrightness(intent.value)
            is ResonatorIntent.Damping -> if (!intent.fromSequencer) engine.setResonatorDamping(intent.value)
            is ResonatorIntent.Position -> if (!intent.fromSequencer) engine.setResonatorPosition(intent.value)
            is ResonatorIntent.Mix -> if (!intent.fromSequencer) engine.setResonatorMix(intent.value)
            is ResonatorIntent.TargetMix -> if (!intent.fromSequencer) engine.setResonatorTargetMix(intent.value)
            is ResonatorIntent.SnapBack -> if (!intent.fromSequencer) engine.setResonatorSnapBack(intent.active)
            is ResonatorIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: ResonatorUiState) {
        engine.setResonatorMode(state.mode.ordinal)
        engine.setResonatorStructure(state.structure)
        engine.setResonatorBrightness(state.brightness)
        engine.setResonatorDamping(state.damping)
        engine.setResonatorPosition(state.position)
        engine.setResonatorMix(state.mix)
        engine.setResonatorTargetMix(state.targetMix)
        engine.setResonatorSnapBack(state.snapBack)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setMode(mode: ResonatorMode) {
        _userIntents.tryEmit(ResonatorIntent.SetMode(mode))
        val value = mode.ordinal.toFloat() / 2f
        synthController.emitControlChange(ControlIds.RESONATOR_MODE, value, ControlEventOrigin.UI)
    }

    fun setTargetMix(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.TargetMix(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_TARGET_MIX, value, ControlEventOrigin.UI)
    }

    fun setSnapBack(active: Boolean) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.SnapBack(active, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_SNAP_BACK, if (active) 1f else 0f, ControlEventOrigin.UI)
    }

    fun setStructure(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.Structure(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_STRUCTURE, value, ControlEventOrigin.UI)
    }

    fun setBrightness(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.Brightness(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_BRIGHTNESS, value, ControlEventOrigin.UI)
    }

    fun setDamping(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.Damping(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_DAMPING, value, ControlEventOrigin.UI)
    }

    fun setPosition(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.Position(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_POSITION, value, ControlEventOrigin.UI)
    }

    fun setMix(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(ResonatorIntent.Mix(value, fromSequencer))
        synthController.emitControlChange(ControlIds.RESONATOR_MIX, value, ControlEventOrigin.UI)
    }

    companion object {
        fun previewFeature(state: ResonatorUiState = ResonatorUiState()): ResonatorFeature =
            object : ResonatorFeature {
                override val stateFlow: StateFlow<ResonatorUiState> = MutableStateFlow(state)
                override val actions: ResonatorPanelActions = ResonatorPanelActions.EMPTY
            }

        @Composable
        fun feature(): ResonatorFeature =
            synthViewModel<ResonatorViewModel, ResonatorFeature>()
    }
}
