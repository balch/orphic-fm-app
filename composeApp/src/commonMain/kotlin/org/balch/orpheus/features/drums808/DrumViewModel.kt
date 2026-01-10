package org.balch.orpheus.features.drums808

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.synthViewModel

@Immutable
data class DrumUiState(
    // Bass Drum
    val bdFrequency: Float = 0.3f, // Maps to ~50Hz
    val bdTone: Float = 0.5f,
    val bdDecay: Float = 0.5f,
    val bdP4: Float = 0.5f,  // AFM (Attack FM)
    val bdP5: Float = 0.5f,  // Self FM
    
    // Snare Drum
    val sdFrequency: Float = 0.4f, // Maps to ~180Hz
    val sdTone: Float = 0.5f,
    val sdDecay: Float = 0.5f,
    val sdP4: Float = 0.5f,  // Snappiness
    
    // Hi-Hat
    val hhFrequency: Float = 0.6f, // Maps to ~400Hz
    val hhTone: Float = 0.5f,
    val hhDecay: Float = 0.5f,
    val hhP4: Float = 0.5f,  // Noisiness
    
    // Trigger States (Visual Feedback)
    val isBdActive: Boolean = false,
    val isSdActive: Boolean = false,
    val isHhActive: Boolean = false
)

data class DrumPanelActions(
    // BD actions
    val setBdFrequency: (Float) -> Unit,
    val setBdTone: (Float) -> Unit,
    val setBdDecay: (Float) -> Unit,
    val setBdP4: (Float) -> Unit,
    val startBdTrigger: () -> Unit,
    val stopBdTrigger: () -> Unit,
    
    // SD actions
    val setSdFrequency: (Float) -> Unit,
    val setSdTone: (Float) -> Unit,
    val setSdDecay: (Float) -> Unit,
    val setSdP4: (Float) -> Unit,
    val startSdTrigger: () -> Unit,
    val stopSdTrigger: () -> Unit,
    
    // HH actions
    val setHhFrequency: (Float) -> Unit,
    val setHhTone: (Float) -> Unit,
    val setHhDecay: (Float) -> Unit,
    val setHhP4: (Float) -> Unit,
    val startHhTrigger: () -> Unit,
    val stopHhTrigger: () -> Unit
) {
    companion object {
        val EMPTY = DrumPanelActions(
            {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}
        )
    }
}

typealias DrumFeature = SynthFeature<DrumUiState, DrumPanelActions>

@Inject
@ViewModelKey(DrumViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DrumViewModel(
    private val synthEngine: SynthEngine
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
        startHhTrigger = ::startHhTrigger,
        stopHhTrigger = { _uiState.update { it.copy(isHhActive = false) } }
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
        val freq = 20f + s.bdFrequency * 180f
        synthEngine.setDrumTone(0, freq, s.bdTone, s.bdDecay, s.bdP4, s.bdP5)
    }
    
    private fun updateSdParams(s: DrumUiState) {
        val freq = 100f + s.sdFrequency * 400f
        synthEngine.setDrumTone(1, freq, s.sdTone, s.sdDecay, s.sdP4, 0.5f)
    }
    
    private fun updateHhParams(s: DrumUiState) {
        val freq = 300f + s.hhFrequency * 700f
        synthEngine.setDrumTone(2, freq, s.hhTone, s.hhDecay, s.hhP4, 0.5f)
    }

    init {
        // Init engin params with default state
        updateBdParams(_uiState.value)
        updateSdParams(_uiState.value)
        updateHhParams(_uiState.value)
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
