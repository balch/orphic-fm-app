package org.balch.orpheus.features.evo

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.utils.PanelViewModel

@Immutable
data class EvoUiState(
    val selectedStrategy: AudioEvolutionStrategy,
    val strategies: List<AudioEvolutionStrategy>,
    val isEnabled: Boolean = false,
    val knob1Value: Float = 0.5f,
    val knob2Value: Float = 0.5f
)

data class EvoPanelActions(
    val onStrategyChange: (AudioEvolutionStrategy) -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onKnob1Change: (Float) -> Unit,
    val onKnob2Change: (Float) -> Unit
) {
    companion object {
        val EMPTY = EvoPanelActions(
            onStrategyChange = {},
            onEnabledChange = {},
            onKnob1Change = {},
            onKnob2Change = {}
        )
    }
}

@ViewModelKey(EvoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class EvoViewModel @Inject constructor(
    strategies: Set<AudioEvolutionStrategy>,
    private val synthEngine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel(), PanelViewModel<EvoUiState, EvoPanelActions> {

    private val log = logging("EvoViewModel")

    // Sort strategies alphabetically by name
    private val sortedStrategies = strategies.sortedBy { it.name }

    private val _currentStrategy = MutableStateFlow(sortedStrategies.first())
    
    private val _uiState = MutableStateFlow(
        EvoUiState(
            selectedStrategy = sortedStrategies.first(),
            strategies = sortedStrategies,
            isEnabled = false
        )
    )
    override val uiState: StateFlow<EvoUiState> = _uiState.asStateFlow()

    private var evoJob: Job? = null

    override val panelActions = EvoPanelActions(
        onStrategyChange = ::selectStrategy,
        onEnabledChange = ::setEnabled,
        onKnob1Change = ::onKnob1Change,
        onKnob2Change = ::onKnob2Change
    )

    init {
        log.info { "Initialized with ${sortedStrategies.size} strategies: ${sortedStrategies.map { it.name }}" }
    }

    private fun selectStrategy(strategy: AudioEvolutionStrategy) {
        if (_currentStrategy.value == strategy) return

        log.info { "Switching strategy: ${_currentStrategy.value.name} → ${strategy.name}" }

        // Deactivate old strategy
        _currentStrategy.value.onDeactivate()

        // Activate new strategy
        strategy.onActivate()
        _currentStrategy.value = strategy

        // Update UI state
        _uiState.update { 
            it.copy(
                selectedStrategy = strategy,
                knob1Value = 0.5f,  // Reset knobs on strategy change
                knob2Value = 0.5f
            )
        }

        // Also set the knob values on the new strategy
        strategy.setKnob1(0.5f)
        strategy.setKnob2(0.5f)

        // Restart loop if running
        if (_uiState.value.isEnabled) {
            startEvolutionLoop()
        }
    }

    private fun setEnabled(enabled: Boolean) {
        log.info { "Evolution ${if (enabled) "enabled" else "disabled"} with ${_currentStrategy.value.name}" }
        
        _uiState.update { it.copy(isEnabled = enabled) }
        
        if (enabled) {
            _currentStrategy.value.onActivate()
            startEvolutionLoop()
        } else {
            stopEvolutionLoop()
            _currentStrategy.value.onDeactivate()
        }
    }

    private fun onKnob1Change(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _currentStrategy.value.setKnob1(clamped)
        _uiState.update { it.copy(knob1Value = clamped) }
    }

    private fun onKnob2Change(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _currentStrategy.value.setKnob2(clamped)
        _uiState.update { it.copy(knob2Value = clamped) }
    }

    private fun startEvolutionLoop() {
        stopEvolutionLoop()
        
        log.debug { "Starting evolution loop with ${_currentStrategy.value.name}" }
        
        evoJob = viewModelScope.launch(dispatcherProvider.default) {
            while (isActive && _uiState.value.isEnabled) {
                val strategy = _currentStrategy.value
                
                // Double-check before evolving
                if (!_uiState.value.isEnabled) {
                    log.debug { "Evolution disabled during loop, breaking" }
                    break
                }
                
                // Execute evolution step
                try {
                    strategy.evolve(synthEngine)
                } catch (e: Exception) {
                    log.error { "Error during evolution: ${e.message}" }
                }

                // Calculate delay based on strategy's speed knob
                // Each strategy handles speed internally, but we use a baseline
                val knob1 = _uiState.value.knob1Value
                // Speed 0.0 = Slow (2000ms), Speed 1.0 = Fast (100ms)
                val delayMs = (2000f - (knob1 * 1900f)).toLong().coerceAtLeast(100L)
                delay(delayMs)
            }
            log.debug { "Evolution loop exited normally" }
        }
    }

    private fun stopEvolutionLoop() {
        if (evoJob != null) {
            log.info { "Stopping evolution loop (job active: ${evoJob?.isActive})" }
        }
        evoJob?.cancel()
        evoJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopEvolutionLoop()
        _currentStrategy.value.onDeactivate()
        log.info { "EvoViewModel cleared" }
    }
}
