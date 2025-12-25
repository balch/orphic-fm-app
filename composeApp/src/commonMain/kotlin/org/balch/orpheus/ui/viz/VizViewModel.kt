package org.balch.orpheus.ui.viz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * UI State for the VIZ panel.
 */
data class VizUiState(
    val selectedViz: Visualization,
    val visualizations: List<Visualization>,
    val showKnobs: Boolean,
    val liquidEffects: VisualizationLiquidEffects = selectedViz.liquidEffects
)

/**
 * ViewModel for managing visualizations.
 * Injects all available Visualization implementations.
 */
@Inject
@ViewModelKey(VizViewModel::class)
@ContributesIntoMap(AppScope::class)
class VizViewModel(
    visualizations: Set<Visualization>,
    private val appPreferencesRepository: AppPreferencesRepository
) : ViewModel() {

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
    val uiState: StateFlow<VizUiState> = _uiState.asStateFlow()

    init {
        // Activate initial visualization if it's not off (likely is off initially)
        if (_currentViz.value.id != "off") {
            _currentViz.value.onActivate()
        }

        viewModelScope.launch {
            val prefs = appPreferencesRepository.load()
            prefs.lastVizId?.let { id ->
                sortedVisualizations.find { it.id == id }?.let { viz ->
                    selectVisualization(viz, save = false)
                }
            }
        }
    }

    /**
     * Select a new visualization by instance.
     */
    private var dynamicEffectsJob: kotlinx.coroutines.Job? = null

    /**
     * Select a new visualization by instance.
     */
    fun selectVisualization(viz: Visualization, save: Boolean = true) {
        if (_currentViz.value == viz) return

        // Deactivate old
        _currentViz.value.onDeactivate()
        dynamicEffectsJob?.cancel()
        dynamicEffectsJob = null

        // Activate new
        viz.onActivate()
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
            viewModelScope.launch {
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
    }

    fun onKnob2Change(value: Float) {
        _currentViz.value.setKnob2(value)
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
}
