package org.balch.orpheus.features.grains

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
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.plugins.grains.engine.GrainsMode

@Immutable
data class GrainsUiState(
    val position: Float = 0.2f,  // Delay Time / Loop Length
    val size: Float = 0.5f,      // Grain Size / Diffusion
    val pitch: Float = 0.0f,     // Pitch Shifting (semitones/ratio)
    val density: Float = 0.5f,   // Feedback / Grain Overlap
    val texture: Float = 0.5f,   // Filter (LP/HP)
    val dryWet: Float = 0.5f,    // Mix (0=dry, 1=wet, 0.5=50/50)
    val freeze: Boolean = false, // Loop/Freeze
    val trigger: Boolean = false, // Trigger
    val mode: GrainsMode = GrainsMode.GRANULAR // Processing mode
)

@Immutable
data class GrainsPanelActions(
    val setPosition: (Float) -> Unit,
    val setSize: (Float) -> Unit,
    val setPitch: (Float) -> Unit,
    val setDensity: (Float) -> Unit,
    val setTexture: (Float) -> Unit,
    val setDryWet: (Float) -> Unit,
    val setFreeze: (Boolean) -> Unit,
    val trigger: () -> Unit,
    val setMode: (GrainsMode) -> Unit
) {
    companion object Companion {
        val EMPTY = GrainsPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

typealias GrainsFeature = SynthFeature<GrainsUiState, GrainsPanelActions>

@Inject
@ViewModelKey(GrainsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class GrainsViewModel(
    private val synthEngine: SynthEngine,
    presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), GrainsFeature {

    private val _uiState = MutableStateFlow(GrainsUiState())
    override val stateFlow: StateFlow<GrainsUiState> = _uiState.asStateFlow()

    override val actions = GrainsPanelActions(
        setPosition = { value ->
            _uiState.update { it.copy(position = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsPosition(value)
            }
        },
        setSize = { value ->
            _uiState.update { it.copy(size = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsSize(value)
            }
        },
        setPitch = { value ->
            _uiState.update { it.copy(pitch = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsPitch(value)
            }
        },
        setDensity = { value ->
            _uiState.update { it.copy(density = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsDensity(value)
            }
        },
        setTexture = { value ->
            _uiState.update { it.copy(texture = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsTexture(value)
            }
        },
        setDryWet = { value ->
            _uiState.update { it.copy(dryWet = value) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsDryWet(value)
            }
        },
        setFreeze = { frozen ->
            _uiState.update { it.copy(freeze = frozen) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsFreeze(frozen)
            }
        },
        trigger = {
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsTrigger(true)
                kotlinx.coroutines.delay(20)
                synthEngine.setGrainsTrigger(false)
            }
        },
        setMode = { mode ->
            _uiState.update { it.copy(mode = mode) }
            viewModelScope.launch(dispatcherProvider.default) {
                synthEngine.setGrainsMode(mode.ordinal)
            }
        }
    )

    init {
        // Initialize engine with defaults
        viewModelScope.launch(dispatcherProvider.default) {
            val state = _uiState.value
            synthEngine.setGrainsPosition(state.position)
            synthEngine.setGrainsSize(state.size)
            synthEngine.setGrainsPitch(state.pitch)
            synthEngine.setGrainsDensity(state.density)
            synthEngine.setGrainsTexture(state.texture)
            synthEngine.setGrainsDryWet(state.dryWet)
            synthEngine.setGrainsFreeze(state.freeze)
            synthEngine.setGrainsMode(state.mode.ordinal)
        }
        
        // Subscribe to preset changes for state restore
        presetLoader.presetFlow
            .onEach { preset ->
                val mode = try {
                    GrainsMode.entries[preset.grainsMode]
                } catch (e: Exception) {
                    GrainsMode.GRANULAR
                }
                _uiState.update {
                    it.copy(
                        position = preset.grainsPosition,
                        size = preset.grainsSize,
                        pitch = preset.grainsPitch,
                        density = preset.grainsDensity,
                        texture = preset.grainsTexture,
                        dryWet = preset.grainsDryWet,
                        freeze = preset.grainsFreeze,
                        mode = mode
                    )
                }
                // Apply to engine
                synthEngine.setGrainsPosition(preset.grainsPosition)
                synthEngine.setGrainsSize(preset.grainsSize)
                synthEngine.setGrainsPitch(preset.grainsPitch)
                synthEngine.setGrainsDensity(preset.grainsDensity)
                synthEngine.setGrainsTexture(preset.grainsTexture)
                synthEngine.setGrainsDryWet(preset.grainsDryWet)
                synthEngine.setGrainsFreeze(preset.grainsFreeze)
                synthEngine.setGrainsMode(preset.grainsMode)
            }
            .flowOn(dispatcherProvider.default)
            .launchIn(viewModelScope)
    }

    companion object Companion {
        fun previewFeature(state: GrainsUiState = GrainsUiState()): GrainsFeature =
            object : GrainsFeature {
                override val stateFlow: StateFlow<GrainsUiState> = MutableStateFlow(state)
                override val actions: GrainsPanelActions = GrainsPanelActions.EMPTY
            }

        @Composable
        fun feature(): GrainsFeature =
            synthViewModel<GrainsViewModel, GrainsFeature>()
    }
}

