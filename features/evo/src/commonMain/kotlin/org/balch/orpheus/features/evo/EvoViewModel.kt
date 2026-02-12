package org.balch.orpheus.features.evo

import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.plugin.symbols.EvoSymbol
import org.balch.orpheus.core.synthViewModel


@Immutable
data class EvoUiState(
    val selectedStrategy: AudioEvolutionStrategy,
    val strategies: List<AudioEvolutionStrategy>,
    val isEnabled: Boolean = false,
    val knob1Value: Float = 0.5f,
    val knob2Value: Float = 0.5f
)

@Immutable
data class EvoPanelActions(
    val setStrategy: (AudioEvolutionStrategy) -> Unit,
    val setEnabled: (Boolean) -> Unit,
    val setKnob1: (Float) -> Unit,
    val setKnob2: (Float) -> Unit
) {
    companion object {
        val EMPTY = EvoPanelActions(
            setStrategy = {},
            setEnabled = {},
            setKnob1 = {},
            setKnob2 = {}
        )
    }
}

typealias EvoFeature = SynthFeature<EvoUiState, EvoPanelActions>

@ViewModelKey(EvoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class EvoViewModel @Inject constructor(
    strategies: Set<AudioEvolutionStrategy>,
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val mediaSessionStateManager: MediaSessionStateManager
) : ViewModel(), EvoFeature {

    private val log = logging("EvoViewModel")

    // Sort strategies alphabetically by name
    private val sortedStrategies = strategies.sortedBy { it.name }

    private val _userIntents = MutableSharedFlow<EvoIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val stateFlow: StateFlow<EvoUiState> = flow {
        val initial = EvoUiState(
            selectedStrategy = sortedStrategies.first(),
            strategies = sortedStrategies,
            isEnabled = false
        )
        emit(initial)
        _userIntents
            .scan(initial) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(state, newState, intent)
                newState
            }
            .collect { emit(it) }
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EvoUiState(
            selectedStrategy = sortedStrategies.first(),
            strategies = sortedStrategies,
            isEnabled = false
        )
    )

    private var evoJob: Job? = null

    override val actions = EvoPanelActions(
        setStrategy = { _userIntents.tryEmit(EvoIntent.Strategy(it)) },
        setEnabled = { _userIntents.tryEmit(EvoIntent.Enabled(it)) },
        setKnob1 = { _userIntents.tryEmit(EvoIntent.Knob1(it)) },
        setKnob2 = { _userIntents.tryEmit(EvoIntent.Knob2(it)) }
    )

    init {
        log.debug { "Initialized with ${sortedStrategies.size} strategies: ${sortedStrategies.map { it.name }}" }

        // Subscribe to MIDI CC changes for evo knobs
        viewModelScope.launch(dispatcherProvider.default) {
            synthController.controlFlow(EvoSymbol.DEPTH.controlId).collect { value ->
                _userIntents.tryEmit(EvoIntent.Knob1(value.asFloat()))
            }
        }
        viewModelScope.launch(dispatcherProvider.default) {
            synthController.controlFlow(EvoSymbol.RATE.controlId).collect { value ->
                _userIntents.tryEmit(EvoIntent.Knob2(value.asFloat()))
            }
        }
    }

    private fun reduce(state: EvoUiState, intent: EvoIntent): EvoUiState =
        when (intent) {
            is EvoIntent.Strategy -> {
                if (state.selectedStrategy == intent.strategy) state
                else state.copy(
                    selectedStrategy = intent.strategy,
                    knob1Value = 0.5f,
                    knob2Value = 0.5f
                )
            }
            is EvoIntent.Enabled -> state.copy(isEnabled = intent.enabled)
            is EvoIntent.Knob1 -> state.copy(knob1Value = intent.value.coerceIn(0f, 1f))
            is EvoIntent.Knob2 -> state.copy(knob2Value = intent.value.coerceIn(0f, 1f))
            is EvoIntent.Restore -> intent.state
        }

    private fun applyToEngine(oldState: EvoUiState, newState: EvoUiState, intent: EvoIntent) {
        when (intent) {
            is EvoIntent.Strategy -> {
                val old = oldState.selectedStrategy
                if (old == intent.strategy) return

                log.debug { "Switching strategy: ${old.name} → ${intent.strategy.name}" }
                old.onDeactivate()
                intent.strategy.onActivate()

                // Reset knobs on strategy change
                intent.strategy.setKnob1(0.5f)
                intent.strategy.setKnob2(0.5f)

                // Restart loop if running
                if (newState.isEnabled) {
                    startEvolutionLoop()
                }
            }
            is EvoIntent.Enabled -> {
                log.debug { "Evolution ${if (intent.enabled) "enabled" else "disabled"} with ${newState.selectedStrategy.name}" }
                mediaSessionStateManager.setEvoActive(intent.enabled)

                if (intent.enabled) {
                    newState.selectedStrategy.onActivate()
                    startEvolutionLoop()
                } else {
                    stopEvolutionLoop()
                    newState.selectedStrategy.onDeactivate()
                }
            }
            is EvoIntent.Knob1 -> {
                newState.selectedStrategy.setKnob1(intent.value.coerceIn(0f, 1f))
            }
            is EvoIntent.Knob2 -> {
                newState.selectedStrategy.setKnob2(intent.value.coerceIn(0f, 1f))
            }
            is EvoIntent.Restore -> {
                oldState.selectedStrategy.onDeactivate()
                intent.state.selectedStrategy.onActivate()
                intent.state.selectedStrategy.setKnob1(intent.state.knob1Value)
                intent.state.selectedStrategy.setKnob2(intent.state.knob2Value)
                mediaSessionStateManager.setEvoActive(intent.state.isEnabled)
                if (intent.state.isEnabled) startEvolutionLoop() else stopEvolutionLoop()
            }
        }
    }

    private fun startEvolutionLoop() {
        stopEvolutionLoop()

        log.debug { "Starting evolution loop with ${stateFlow.value.selectedStrategy.name}" }

        evoJob = viewModelScope.launch(dispatcherProvider.default) {
            while (isActive) {
                val state = stateFlow.value
                if (!state.isEnabled) break

                // Execute evolution step
                try {
                    state.selectedStrategy.evolve(synthEngine)
                } catch (e: Exception) {
                    log.error { "Error during evolution: ${e.message}" }
                }

                // Calculate delay based on strategy's speed knob
                // Speed 0.0 = Slow (2000ms), Speed 1.0 = Fast (100ms)
                val delayMs = (2000f - (state.knob1Value * 1900f)).toLong().coerceAtLeast(100L)
                delay(delayMs)
            }
            log.debug { "Evolution loop exited normally" }
        }
    }

    private fun stopEvolutionLoop() {
        if (evoJob != null) {
            log.debug { "Stopping evolution loop (job active: ${evoJob?.isActive})" }
        }
        evoJob?.cancel()
        evoJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopEvolutionLoop()
        stateFlow.value.selectedStrategy.onDeactivate()
        log.debug { "EvoViewModel cleared" }
    }

    companion object {
        private object PreviewStrategy : AudioEvolutionStrategy {
            override val id = "preview"
            override val name = "Preview"
            override val color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
            override val knob1Label = "SPEED"
            override val knob2Label = "RANGE"
            override fun setKnob1(value: Float) {}
            override fun setKnob2(value: Float) {}
            override suspend fun evolve(engine: SynthEngine) {}
            override fun onActivate() {}
            override fun onDeactivate() {}
        }

        fun previewFeature(state: EvoUiState = EvoUiState(
            selectedStrategy = PreviewStrategy,
            strategies = listOf(PreviewStrategy),
            isEnabled = false,
            knob1Value = 0.5f,
            knob2Value = 0.5f
        )): EvoFeature =
            object : EvoFeature {
                override val stateFlow: StateFlow<EvoUiState> = MutableStateFlow(state)
                override val actions: EvoPanelActions = EvoPanelActions.EMPTY
            }

        @Composable
        fun feature(): EvoFeature =
            synthViewModel<EvoViewModel, EvoFeature>()
    }
}

private sealed interface EvoIntent {
    data class Strategy(val strategy: AudioEvolutionStrategy) : EvoIntent
    data class Enabled(val enabled: Boolean) : EvoIntent
    data class Knob1(val value: Float) : EvoIntent
    data class Knob2(val value: Float) : EvoIntent
    data class Restore(val state: EvoUiState) : EvoIntent
}
