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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

/**
 * Resonator synthesis mode (ported from Mutable Instruments Rings).
 */
enum class ResonatorMode(val displayName: String) {
    MODAL("Modal"),      // Bell/plate/bar resonance
    STRING("String"),    // Karplus-Strong string
    SYMPATHETIC("Sympa") // Sympathetic strings (sitar-like)
}

/**
 * UI state for the Rings Resonator.
 */
@Immutable
data class ResonatorUiState(
    val enabled: Boolean = false,
    val mode: ResonatorMode = ResonatorMode.MODAL,
    val structure: Float = 0.25f,    // Material/inharmonicity (0-1)
    val brightness: Float = 0.5f,    // High freq content (0-1)
    val damping: Float = 0.3f,       // Decay time (0-1)
    val position: Float = 0.5f,      // Excitation point (0-1)
    val mix: Float = 0.5f            // Dry/wet (0-1)
)

/**
 * Actions for controlling the resonator.
 */
@Immutable
data class ResonatorPanelActions(
    val setEnabled: (Boolean) -> Unit,
    val setMode: (ResonatorMode) -> Unit,
    val setStructure: (Float) -> Unit,
    val setBrightness: (Float) -> Unit,
    val setDamping: (Float) -> Unit,
    val setPosition: (Float) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object {
        val EMPTY = ResonatorPanelActions({}, {}, {}, {}, {}, {}, {})
    }
}

typealias ResonatorFeature = SynthFeature<ResonatorUiState, ResonatorPanelActions>

@Inject
@ViewModelKey(ResonatorViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ResonatorViewModel(
    private val synthEngine: SynthEngine,
    private val synthController: SynthController
) : ViewModel(), ResonatorFeature {

    private val _uiState = MutableStateFlow(ResonatorUiState())
    override val stateFlow: StateFlow<ResonatorUiState> = _uiState.asStateFlow()

    override val actions = ResonatorPanelActions(
        setEnabled = { enabled ->
            _uiState.update { it.copy(enabled = enabled) }
            synthEngine.setResonatorEnabled(enabled)
        },
        setMode = { mode ->
            _uiState.update { it.copy(mode = mode) }
            synthEngine.setResonatorMode(mode.ordinal)
        },
        setStructure = { structure ->
            _uiState.update { it.copy(structure = structure) }
            synthEngine.setResonatorStructure(structure)
        },
        setBrightness = { brightness ->
            _uiState.update { it.copy(brightness = brightness) }
            synthEngine.setResonatorBrightness(brightness)
        },
        setDamping = { damping ->
            _uiState.update { it.copy(damping = damping) }
            synthEngine.setResonatorDamping(damping)
        },
        setPosition = { position ->
            _uiState.update { it.copy(position = position) }
            synthEngine.setResonatorPosition(position)
        },
        setMix = { mix ->
            _uiState.update { it.copy(mix = mix) }
            synthEngine.setResonatorMix(mix)
        }
    )

    init {
        // Initialize engine with default values
        synthEngine.setResonatorEnabled(_uiState.value.enabled)
        synthEngine.setResonatorMode(_uiState.value.mode.ordinal)
        synthEngine.setResonatorStructure(_uiState.value.structure)
        synthEngine.setResonatorBrightness(_uiState.value.brightness)
        synthEngine.setResonatorDamping(_uiState.value.damping)
        synthEngine.setResonatorPosition(_uiState.value.position)
        synthEngine.setResonatorMix(_uiState.value.mix)
        
        // Subscribe to SynthController events for AI/MIDI control
        synthController.onControlChange
            .onEach { event ->
                when (event.controlId) {
                    ControlIds.RESONATOR_ENABLED -> {
                        val enabled = event.value >= 0.5f
                        _uiState.update { it.copy(enabled = enabled) }
                        synthEngine.setResonatorEnabled(enabled)
                    }
                    ControlIds.RESONATOR_MODE -> {
                        // 0 = Modal, 0.5 = String, 1 = Sympathetic
                        val mode = when {
                            event.value < 0.33f -> ResonatorMode.MODAL
                            event.value < 0.66f -> ResonatorMode.STRING
                            else -> ResonatorMode.SYMPATHETIC
                        }
                        _uiState.update { it.copy(mode = mode) }
                        synthEngine.setResonatorMode(mode.ordinal)
                    }
                    ControlIds.RESONATOR_STRUCTURE -> {
                        _uiState.update { it.copy(structure = event.value) }
                        synthEngine.setResonatorStructure(event.value)
                    }
                    ControlIds.RESONATOR_BRIGHTNESS -> {
                        _uiState.update { it.copy(brightness = event.value) }
                        synthEngine.setResonatorBrightness(event.value)
                    }
                    ControlIds.RESONATOR_DAMPING -> {
                        _uiState.update { it.copy(damping = event.value) }
                        synthEngine.setResonatorDamping(event.value)
                    }
                    ControlIds.RESONATOR_POSITION -> {
                        _uiState.update { it.copy(position = event.value) }
                        synthEngine.setResonatorPosition(event.value)
                    }
                    ControlIds.RESONATOR_MIX -> {
                        _uiState.update { it.copy(mix = event.value) }
                        synthEngine.setResonatorMix(event.value)
                    }
                }
            }
            .launchIn(viewModelScope)
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

