package org.balch.orpheus.features.drum

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

enum class DrumTriggerSource(val displayName: String) {
    INTERNAL("Internal"),
    FLUX_T1("Flux T1"),
    FLUX_T2("Flux T2"),
    FLUX_T3("Flux T3")
}

@Immutable
data class DrumUiState(
    // Bass Drum
    val bdFrequency: Float = 0.3f, // Maps to ~50Hz
    val bdTone: Float = 0.5f,
    val bdDecay: Float = 0.5f,
    val bdP4: Float = 0.5f,  // AFM (Attack FM)
    val bdP5: Float = 0.5f,  // Self FM
    val bdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Snare Drum
    val sdFrequency: Float = 0.4f, // Maps to ~180Hz
    val sdTone: Float = 0.5f,
    val sdDecay: Float = 0.5f,
    val sdP4: Float = 0.5f,  // Snappiness
    val sdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Hi-Hat
    val hhFrequency: Float = 0.6f, // Maps to ~400Hz
    val hhTone: Float = 0.5f,
    val hhDecay: Float = 0.5f,
    val hhP4: Float = 0.5f,  // Noisiness
    val hhTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Trigger States (Visual Feedback)
    val isBdActive: Boolean = false,
    val isSdActive: Boolean = false,
    val isHhActive: Boolean = false,
    val drumsBypass: Boolean = true
)

data class DrumPanelActions(
    // BD actions
    val setBdFrequency: (Float) -> Unit,
    val setBdTone: (Float) -> Unit,
    val setBdDecay: (Float) -> Unit,
    val setBdP4: (Float) -> Unit,
    val setBdTriggerSource: (DrumTriggerSource) -> Unit,
    val startBdTrigger: () -> Unit,
    val stopBdTrigger: () -> Unit,
    
    // SD actions
    val setSdFrequency: (Float) -> Unit,
    val setSdTone: (Float) -> Unit,
    val setSdDecay: (Float) -> Unit,
    val setSdP4: (Float) -> Unit,
    val setSdTriggerSource: (DrumTriggerSource) -> Unit,
    val startSdTrigger: () -> Unit,
    val stopSdTrigger: () -> Unit,
    
    // HH actions
    val setHhFrequency: (Float) -> Unit,
    val setHhTone: (Float) -> Unit,
    val setHhDecay: (Float) -> Unit,
    val setHhP4: (Float) -> Unit,
    val setHhTriggerSource: (DrumTriggerSource) -> Unit,
    val startHhTrigger: () -> Unit,
    val stopHhTrigger: () -> Unit,
    
    // Global
    val setDrumsBypass: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DrumPanelActions(
            {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {}, {}
        )
    }
}

typealias DrumFeature = SynthFeature<DrumUiState, DrumPanelActions>

@Inject
@ViewModelKey(DrumViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DrumViewModel(
    private val synthEngine: SynthEngine,
    presetLoader: PresetLoader,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel(), DrumFeature {

    private val _uiState = MutableStateFlow(DrumUiState())
    override val stateFlow: StateFlow<DrumUiState> = _uiState.asStateFlow()

    override val actions = DrumPanelActions(
        // BD actions
        setBdFrequency = { f -> 
            _uiState.update { it.copy(bdFrequency = f) }
            updateBdParams(_uiState.value)
        },
        setBdTone = { t -> 
            _uiState.update { it.copy(bdTone = t) }
            updateBdParams(_uiState.value)
        },
        setBdDecay = { d -> 
            _uiState.update { it.copy(bdDecay = d) }
            updateBdParams(_uiState.value)
        },
        setBdP4 = { p -> 
            _uiState.update { it.copy(bdP4 = p) }
            updateBdParams(_uiState.value)
        },
        setBdTriggerSource = { src ->
            _uiState.update { it.copy(bdTriggerSource = src) }
            synthEngine.setDrumTriggerSource(0, src.ordinal)
        },
        startBdTrigger = ::startBdTrigger,
        stopBdTrigger = { _uiState.update { it.copy(isBdActive = false) } },
        
        // SD actions
        setSdFrequency = { f -> 
            _uiState.update { it.copy(sdFrequency = f) }
            updateSdParams(_uiState.value)
        },
        setSdTone = { t -> 
            _uiState.update { it.copy(sdTone = t) }
            updateSdParams(_uiState.value)
        },
        setSdDecay = { d -> 
            _uiState.update { it.copy(sdDecay = d) }
            updateSdParams(_uiState.value)
        },
        setSdP4 = { p -> 
            _uiState.update { it.copy(sdP4 = p) }
            updateSdParams(_uiState.value)
        },
        setSdTriggerSource = { src ->
            _uiState.update { it.copy(sdTriggerSource = src) }
            synthEngine.setDrumTriggerSource(1, src.ordinal)
        },
        startSdTrigger = ::startSdTrigger,
        stopSdTrigger = { _uiState.update { it.copy(isSdActive = false) } },
        
        // HH actions
        setHhFrequency = { f -> 
            _uiState.update { it.copy(hhFrequency = f) }
            updateHhParams(_uiState.value)
        },
        setHhTone = { t -> 
            _uiState.update { it.copy(hhTone = t) }
            updateHhParams(_uiState.value)
        },
        setHhDecay = { d -> 
            _uiState.update { it.copy(hhDecay = d) }
            updateHhParams(_uiState.value)
        },
        setHhP4 = { p -> 
            _uiState.update { it.copy(hhP4 = p) }
            updateHhParams(_uiState.value)
        },
        setHhTriggerSource = { src ->
            _uiState.update { it.copy(hhTriggerSource = src) }
            synthEngine.setDrumTriggerSource(2, src.ordinal)
        },
        startHhTrigger = ::startHhTrigger,
        stopHhTrigger = { _uiState.update { it.copy(isHhActive = false) } },
        setDrumsBypass = { bypass ->
            _uiState.update { it.copy(drumsBypass = bypass) }
            synthEngine.setDrumsBypass(bypass)
        }
    )

    private fun startBdTrigger() {
        _uiState.update { it.copy(isBdActive = true) }
        synthEngine.triggerDrum(0, 1.0f)
    }

    private fun startSdTrigger() {
        _uiState.update { it.copy(isSdActive = true) }
        synthEngine.triggerDrum(1, 1.0f)
    }

    private fun startHhTrigger() {
        _uiState.update { it.copy(isHhActive = true) }
        synthEngine.triggerDrum(2, 1.0f)
    }
    
    // Helper to send params to engine
    private fun updateBdParams(s: DrumUiState) {
        synthEngine.setDrumTone(0, s.bdFrequency, s.bdTone, s.bdDecay, s.bdP4, s.bdP5)
    }
    
    private fun updateSdParams(s: DrumUiState) {
        synthEngine.setDrumTone(1, s.sdFrequency, s.sdTone, s.sdDecay, s.sdP4, 0.5f)
    }
    
    private fun updateHhParams(s: DrumUiState) {
        synthEngine.setDrumTone(2, s.hhFrequency, s.hhTone, s.hhDecay, s.hhP4, 0.5f)
    }

    init {
        // Init engin params with default state
        updateBdParams(_uiState.value)
        updateSdParams(_uiState.value)
        updateHhParams(_uiState.value)
        synthEngine.setDrumsBypass(_uiState.value.drumsBypass)
        
        // Subscribe to presets
        viewModelScope.launch(dispatcherProvider.default) {
            presetLoader.presetFlow.collect { preset ->
                _uiState.update {
                    it.copy(
                        bdFrequency = preset.drumBdFrequency,
                        bdTone = preset.drumBdTone,
                        bdDecay = preset.drumBdDecay,
                        bdP4 = preset.drumBdP4,
                        bdP5 = preset.drumBdP5,
                        bdTriggerSource = DrumTriggerSource.entries.getOrElse(preset.drumBdTriggerSource) { DrumTriggerSource.INTERNAL },
                        
                        sdFrequency = preset.drumSdFrequency,
                        sdTone = preset.drumSdTone,
                        sdDecay = preset.drumSdDecay,
                        sdP4 = preset.drumSdP4,
                        sdTriggerSource = DrumTriggerSource.entries.getOrElse(preset.drumSdTriggerSource) { DrumTriggerSource.INTERNAL },
                        
                        hhFrequency = preset.drumHhFrequency,
                        hhTone = preset.drumHhTone,
                        hhDecay = preset.drumHhDecay,
                        hhP4 = preset.drumHhP4,
                        hhTriggerSource = DrumTriggerSource.entries.getOrElse(preset.drumHhTriggerSource) { DrumTriggerSource.INTERNAL },
                        
                        drumsBypass = preset.drumsBypass
                    )
                }
                // Push to engine
                val s = _uiState.value
                updateBdParams(s)
                updateSdParams(s)
                updateHhParams(s)
                synthEngine.setDrumsBypass(s.drumsBypass)
                synthEngine.setDrumTriggerSource(0, s.bdTriggerSource.ordinal)
                synthEngine.setDrumTriggerSource(1, s.sdTriggerSource.ordinal)
                synthEngine.setDrumTriggerSource(2, s.hhTriggerSource.ordinal)
            }
        }

        // Subscribe to controller events
        viewModelScope.launch(dispatcherProvider.default) {
            synthController.onControlChange.collect { event ->
                when (event.controlId) {
                    ControlIds.DRUM_BD_FREQ -> actions.setBdFrequency(event.value)
                    ControlIds.DRUM_BD_TONE -> actions.setBdTone(event.value)
                    ControlIds.DRUM_BD_DECAY -> actions.setBdDecay(event.value)
                    ControlIds.DRUM_BD_AFM -> actions.setBdP4(event.value)
                    ControlIds.DRUM_BD_TRIGGER -> if (event.value >= 0.5f) actions.startBdTrigger() else actions.stopBdTrigger()

                    ControlIds.DRUM_SD_FREQ -> actions.setSdFrequency(event.value)
                    ControlIds.DRUM_SD_TONE -> actions.setSdTone(event.value)
                    ControlIds.DRUM_SD_DECAY -> actions.setSdDecay(event.value)
                    ControlIds.DRUM_SD_SNAPPY -> actions.setSdP4(event.value)
                    ControlIds.DRUM_SD_TRIGGER -> if (event.value >= 0.5f) actions.startSdTrigger() else actions.stopSdTrigger()

                    ControlIds.DRUM_HH_FREQ -> actions.setHhFrequency(event.value)
                    ControlIds.DRUM_HH_TONE -> actions.setHhTone(event.value)
                    ControlIds.DRUM_HH_DECAY -> actions.setHhDecay(event.value)
                    ControlIds.DRUM_HH_NOISY -> actions.setHhP4(event.value)
                    ControlIds.DRUM_HH_TRIGGER -> if (event.value >= 0.5f) actions.startHhTrigger() else actions.stopHhTrigger()
                    ControlIds.DRUMS_BYPASS -> actions.setDrumsBypass(event.value >= 0.5f)
                }
            }
        }
    }

    companion object {
        fun previewFeature(state: DrumUiState = DrumUiState()): DrumFeature =
            object : DrumFeature {
                override val stateFlow: StateFlow<DrumUiState> = MutableStateFlow(state)
                override val actions: DrumPanelActions = DrumPanelActions.EMPTY
            }

        @Composable
        fun feature(): DrumFeature =
            synthViewModel<DrumViewModel, DrumFeature>()
    }
}
