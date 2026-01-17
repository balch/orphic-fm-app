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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
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

typealias ResonatorFeature = SynthFeature<ResonatorUiState, ResonatorPanelActions>

@Inject
@ViewModelKey(ResonatorViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ResonatorViewModel(
    private val synthEngine: SynthEngine,
    synthController: SynthController,
    presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), ResonatorFeature {

    private val _uiState = MutableStateFlow(ResonatorUiState())
    override val stateFlow: StateFlow<ResonatorUiState> = _uiState.asStateFlow()

    override val actions = ResonatorPanelActions(
        setMode = { mode ->
            _uiState.update { it.copy(mode = mode) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorMode(mode.ordinal)
            }
        },
        setTargetMix = { targetMix ->
            _uiState.update { it.copy(targetMix = targetMix) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorTargetMix(targetMix)
            }
        },
        setSnapBack = { snapBack ->
            _uiState.update { it.copy(snapBack = snapBack) }
            synthEngine.setResonatorSnapBack(snapBack)
        },
        setStructure = { structure ->
            _uiState.update { it.copy(structure = structure) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorStructure(structure)
            }
        },
        setBrightness = { brightness ->
            _uiState.update { it.copy(brightness = brightness) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorBrightness(brightness)
            }
        },
        setDamping = { damping ->
            _uiState.update { it.copy(damping = damping) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorDamping(damping)
            }
        },
        setPosition = { position ->
            _uiState.update { it.copy(position = position) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorPosition(position)
            }
        },
        setMix = { mix ->
            _uiState.update { it.copy(mix = mix) }
            viewModelScope.launch(dispatcherProvider.default) { // Move to BG
                synthEngine.setResonatorMix(mix)
            }
        }
    )

    init {
        // Initialize engine with default values (on background thread)
        viewModelScope.launch(dispatcherProvider.default) {
            val state = _uiState.value
            synthEngine.setResonatorMode(state.mode.ordinal)
            synthEngine.setResonatorTargetMix(state.targetMix)
            synthEngine.setResonatorStructure(state.structure)
            synthEngine.setResonatorBrightness(state.brightness)
            synthEngine.setResonatorDamping(state.damping)
            synthEngine.setResonatorPosition(state.position)
            synthEngine.setResonatorMix(state.mix)
        }
        
        // Subscribe to SynthController events for AI/MIDI control
        synthController.onControlChange
            .onEach { event ->
                // ... (existing processing)
                when (event.controlId) {
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
            .flowOn(dispatcherProvider.default)
            .launchIn(viewModelScope)
        
        // Subscribe to preset changes for state restore
        presetLoader.presetFlow
            .onEach { preset ->
                val mode = try {
                    ResonatorMode.entries[preset.resonatorMode]
                } catch (e: Exception) {
                    ResonatorMode.MODAL
                }
                _uiState.update {
                    it.copy(
                        mode = mode,
                        targetMix = preset.resonatorTargetMix,
                        snapBack = preset.resonatorSnapBack,
                        structure = preset.resonatorStructure,
                        brightness = preset.resonatorBrightness,
                        damping = preset.resonatorDamping,
                        position = preset.resonatorPosition,
                        mix = preset.resonatorMix
                    )
                }
                // Apply to engine
                synthEngine.setResonatorMode(preset.resonatorMode)
                synthEngine.setResonatorTargetMix(preset.resonatorTargetMix)
                synthEngine.setResonatorSnapBack(preset.resonatorSnapBack)
                synthEngine.setResonatorStructure(preset.resonatorStructure)
                synthEngine.setResonatorBrightness(preset.resonatorBrightness)
                synthEngine.setResonatorDamping(preset.resonatorDamping)
                synthEngine.setResonatorPosition(preset.resonatorPosition)
                synthEngine.setResonatorMix(preset.resonatorMix)
            }
            .flowOn(dispatcherProvider.default)
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

