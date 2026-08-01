package org.balch.orpheus.features.visualizations

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.preferences.AppPreferencesRepository
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
    val liquidEffects: VisualizationLiquidEffects = selectedViz.liquidEffects,
    val signalVizEnabled: Boolean = false,
    val isRandomVizMode: Boolean = false,
)

data class VizPanelActions(
    val onSelectViz: (Visualization) -> Unit,
    val onKnob1Change: (Float) -> Unit,
    val onKnob2Change: (Float) -> Unit,
    val onToggleSignalViz: (Boolean) -> Unit,
    val onSetRandomMode: (Boolean) -> Unit = {},
    val onSelectRandomViz: () -> Unit = {},
) {
    companion object {
        val EMPTY = VizPanelActions(
            onSelectViz = {},
            onKnob1Change = {},
            onKnob2Change = {},
            onToggleSignalViz = {},
        )
    }
}

interface VizFeature : SynthFeature<VizUiState, VizPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 * ViewModel for managing visualizations.
 * Injects all available Visualization implementations.
 *
 * **Threading contract**: [Visualization.onActivate], [Visualization.onDeactivate], and
 * [Visualization.setKnob1]/[Visualization.setKnob2] must all be called on the main thread.
 * Visualization internal state is owned by the main-thread Compose frame loop (`withFrameNanos`),
 * so any mutation from a background thread would race with the frame loop and can cause
 * `ConcurrentModificationException`. Preference persistence is the only operation intentionally
 * dispatched to [DispatcherProvider.default].
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(VizFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<VizFeature>())
class VizViewModel(
    visualizations: Set<Visualization>,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope
) : VizFeature {

    override val actions = VizPanelActions(
        onSelectViz = { selectVisualization(it) },
        onKnob1Change = ::onKnob1Change,
        onKnob2Change = ::onKnob2Change,
        onToggleSignalViz = ::onToggleSignalViz,
        onSetRandomMode = ::setRandomMode,
        onSelectRandomViz = ::selectRandomVisualization,
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
        // Activate initial visualization if it's not off (likely is off initially).
        // Must run on main — Visualization internal state is owned by the main-thread frame loop.
        if (_currentViz.value.id != "off") {
            scope.launch(dispatcherProvider.main) {
                _currentViz.value.onActivate()
            }
        }

        scope.launch(dispatcherProvider.default) {
            val prefs = appPreferencesRepository.load()
            _uiState.update {
                it.copy(
                    signalVizEnabled = prefs.signalVizEnabled,
                    isRandomVizMode = prefs.randomVizMode,
                )
            }
            if (prefs.randomVizMode) {
                selectRandomVisualization()
            } else {
                prefs.lastVizId?.let { id ->
                    sortedVisualizations.find { it.id == id }?.let { viz ->
                        selectVisualization(viz, save = false)
                    }
                }
            }
        }
        
        // Subscribe to viz knob control flows (bidirectional with MIDI)
        // Collect on Main to serialize updates and prevent out-of-order processing
        scope.launch(dispatcherProvider.main) {
            synthController.controlFlow(VizSymbol.KNOB_1.controlId).collect { value ->
                onKnob1Change(value.asFloat())
            }
        }
        scope.launch(dispatcherProvider.main) {
            synthController.controlFlow(VizSymbol.KNOB_2.controlId).collect { value ->
                onKnob2Change(value.asFloat())
            }
        }
    }

    /**
     * Select a new visualization by instance.
     */
    private var dynamicEffectsJob: Job? = null

    /**
     * Select a new visualization by instance.
     * When [save] is true (explicit user pick), random mode is disabled.
     */
    fun selectVisualization(viz: Visualization, save: Boolean = true) {
        if (_currentViz.value == viz) {
            if (save && _uiState.value.isRandomVizMode) {
                _uiState.update { it.copy(isRandomVizMode = false) }
                scope.launch(dispatcherProvider.default) {
                    appPreferencesRepository.update {
                        it.copy(lastVizId = viz.id, randomVizMode = false)
                    }
                }
            }
            return
        }

        // Lifecycle mutations (onDeactivate/onActivate/setKnob) are confined to main —
        // Visualization internal state is owned by the main-thread Compose frame loop.
        // Preference persistence is dispatched to default inside withContext to keep I/O
        // off the main thread.
        scope.launch(dispatcherProvider.main) {
            _currentViz.value.onDeactivate()
            viz.onActivate()

            dynamicEffectsJob?.cancel()
            dynamicEffectsJob = null

            _currentViz.value = viz

            if (viz is DynamicVisualization) {
                // liquidEffectsFlow collection only writes to a thread-safe MutableStateFlow,
                // so it is safe to run on the default dispatcher.
                dynamicEffectsJob = scope.launch(dispatcherProvider.default) {
                    viz.liquidEffectsFlow.collect { effects ->
                         _uiState.update { it.copy(liquidEffects = effects) }
                    }
                }
            }

            updateState()

            if (save) {
                _uiState.update { it.copy(isRandomVizMode = false) }
                withContext(dispatcherProvider.default) {
                    appPreferencesRepository.update {
                        it.copy(lastVizId = viz.id, randomVizMode = false)
                    }
                }
            }
        }
    }

    fun selectVisualization(viz: Visualization) {
        selectVisualization(viz, save = true)
    }

    private fun setRandomMode(enabled: Boolean) {
        _uiState.update { it.copy(isRandomVizMode = enabled) }
        scope.launch(dispatcherProvider.default) {
            appPreferencesRepository.update { it.copy(randomVizMode = enabled) }
        }
        if (enabled) selectRandomVisualization()
    }

    private fun selectRandomVisualization() {
        val candidates = sortedVisualizations.filter { it.id != "off" && it != _currentViz.value }
        val pick = candidates.randomOrNull() ?: sortedVisualizations.firstOrNull { it.id != "off" } ?: return
        selectVisualization(pick, save = false)
    }

    fun onKnob1Change(value: Float) {
        _currentViz.value.setKnob1(value)
        _uiState.update { it.copy(knob1Value = value) }
    }

    fun onKnob2Change(value: Float) {
        _currentViz.value.setKnob2(value)
        _uiState.update { it.copy(knob2Value = value) }
    }

    private fun onToggleSignalViz(enabled: Boolean) {
        _uiState.update { it.copy(signalVizEnabled = enabled) }
        scope.launch(dispatcherProvider.default) {
            appPreferencesRepository.update { it.copy(signalVizEnabled = enabled) }
        }
    }

    private fun updateState() {
        _uiState.update {
            it.copy(
                selectedViz = _currentViz.value,
                showKnobs = _currentViz.value.id != "off",
                liquidEffects = _currentViz.value.liquidEffects
            )
        }
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
            synthFeature<VizFeature>()
    }
}
