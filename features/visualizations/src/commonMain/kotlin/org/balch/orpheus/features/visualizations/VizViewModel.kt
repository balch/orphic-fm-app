package org.balch.orpheus.features.visualizations

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.visualizations.viz.OffViz
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.DynamicVisualization
import org.balch.orpheus.ui.viz.Visualization


/**
 * UI State for the VIZ panel.
 */
data class VizUiState(
    val selectedViz: Visualization,
    val visualizations: List<Visualization>,
    val showKnobs: Boolean,
    val knob1Value: Float = 0.5f,
    val knob2Value: Float = 0.5f,
    val liquidEffects: VisualizationLiquidEffects = selectedViz.liquidEffects
)

data class VizPanelActions(
    val onSelectViz: (Visualization) -> Unit,
    val onKnob1Change: (Float) -> Unit,
    val onKnob2Change: (Float) -> Unit
) {
    companion object {
        val EMPTY = VizPanelActions(
            onSelectViz = {},
            onKnob1Change = {},
            onKnob2Change = {}
        )
    }
}

typealias VizFeature = SynthFeature<VizUiState, VizPanelActions>

/**
 * ViewModel for managing visualizations.
 * Injects all available Visualization implementations.
 */
@Inject
@ViewModelKey(VizViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class VizViewModel(
    visualizations: Set<Visualization>,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), VizFeature {

    override val actions = VizPanelActions(
        onSelectViz = { selectVisualization(it) },
        onKnob1Change = ::onKnob1Change,
        onKnob2Change = ::onKnob2Change
    )

    // Sorted list: Off first, then alphabetical by name
    private val sortedVisualizations = visualizations.sortedWith(
        compareBy<Visualization> { it.id != "off" }.thenBy { it.name }
    )

    // Current selection
    private val _currentViz = MutableStateFlow(sortedVisualizations.first())

    private val _uiState = MutableStateFlow(
        VizUiState(
            selectedViz = sortedVisualizations.first(),
            visualizations = sortedVisualizations,
            showKnobs = sortedVisualizations.first().id != "off",
            liquidEffects = sortedVisualizations.first().liquidEffects
        )
    )
    override val stateFlow: StateFlow<VizUiState> = _uiState.asStateFlow()

    init {
        // Activate initial visualization if it's not off (likely is off initially)
        if (_currentViz.value.id != "off") {
            viewModelScope.launch(dispatcherProvider.default) {
                _currentViz.value.onActivate()
            }
        }

        viewModelScope.launch(dispatcherProvider.default) {
            val prefs = appPreferencesRepository.load()
            prefs.lastVizId?.let { id ->
                sortedVisualizations.find { it.id == id }?.let { viz ->
                    selectVisualization(viz, save = false)
                }
            }
        }
        
        // Subscribe to control changes for viz knobs
        viewModelScope.launch(dispatcherProvider.default) {
            synthController.onControlChange.collect { event ->
                when (event.controlId) {
                    ControlIds.VIZ_KNOB_1 -> onKnob1Change(event.value)
                    ControlIds.VIZ_KNOB_2 -> onKnob2Change(event.value)
                }
            }
        }
    }

    /**
     * Select a new visualization by instance.
     */
    private var dynamicEffectsJob: Job? = null

    /**
     * Select a new visualization by instance.
     */
    fun selectVisualization(viz: Visualization, save: Boolean = true) {
        if (_currentViz.value == viz) return

        viewModelScope.launch(dispatcherProvider.default) {
            // Perform activation/deactivation on background thread
            _currentViz.value.onDeactivate()
            viz.onActivate()

            dynamicEffectsJob?.cancel()
            dynamicEffectsJob = null
            
            _currentViz.value = viz

            // Handle dynamic effects
            if (viz is DynamicVisualization) {
                dynamicEffectsJob = viewModelScope.launch {
                    viz.liquidEffectsFlow.collect { effects ->
                         _uiState.value = _uiState.value.copy(liquidEffects = effects)
                    }
                }
            }

            updateState()

            if (save) {
                val prefs = appPreferencesRepository.load().copy(lastVizId = viz.id)
                appPreferencesRepository.save(prefs)
            }
        }
    }

    fun selectVisualization(viz: Visualization) {
        selectVisualization(viz, save = true)
    }

    fun onKnob1Change(value: Float) {
        _currentViz.value.setKnob1(value)
        _uiState.value = _uiState.value.copy(knob1Value = value)
    }

    fun onKnob2Change(value: Float) {
        _currentViz.value.setKnob2(value)
        _uiState.value = _uiState.value.copy(knob2Value = value)
    }

    private fun updateState() {
        _uiState.value = _uiState.value.copy(
            selectedViz = _currentViz.value,
            showKnobs = _currentViz.value.id != "off",
            liquidEffects = _currentViz.value.liquidEffects
        )
    }
    
    override fun onCleared() {
        super.onCleared()
        _currentViz.value.onDeactivate()
        dynamicEffectsJob?.cancel()
    }

    companion object {
        fun previewFeature(state: VizUiState = VizUiState(
            selectedViz = OffViz(),
            visualizations = listOf(OffViz()),
            showKnobs = false
        )): VizFeature =
            object : VizFeature {
                override val stateFlow: StateFlow<VizUiState> = MutableStateFlow(state)
                override val actions: VizPanelActions = VizPanelActions.EMPTY
            }

        @Composable
        fun feature(): VizFeature =
            synthViewModel<VizViewModel, VizFeature>()
    }
}
