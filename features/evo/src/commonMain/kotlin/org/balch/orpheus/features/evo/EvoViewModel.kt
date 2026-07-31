package org.balch.orpheus.features.evo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.plugin.symbols.EvoSymbol


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

interface EvoFeature : SynthFeature<EvoUiState, EvoPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.EVO
            override val title = "Evolution"

            override val markdown = """
        Automated sound evolution engine. Gradually morphs synth parameters over time using different strategies. Controls depth, rate, and variation.

        ## Controls
        - **DEPTH**: How far parameters deviate from their current values during evolution.
        - **RATE**: Speed of the evolution cycle. Higher values = faster parameter changes.

        ## Switches
        - **STRATEGY**: Selects the evolution algorithm (e.g., Random Walk, Drift, Morph).
        - **ENABLED**: Toggles the evolution engine on/off.

        ## Tips
        - Start with low DEPTH for subtle, slow-moving timbral shifts.
        - Combine with the LFO panel for layered modulation effects.
            """.trimIndent()

            override val portControlKeys = mapOf(
                EvoSymbol.DEPTH.controlId.key to "Parameter deviation depth",
                EvoSymbol.RATE.controlId.key to "Evolution cycle speed",
                EvoSymbol.VARIATION.controlId.key to "Variation amount / randomness",
            )
        }
    }
}

@Inject
@SynthFeatureKey(EvoFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class EvoViewModel(
    strategies: Set<AudioEvolutionStrategy>,
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val mediaSessionStateManager: MediaSessionStateManager,
    private val scope: FeatureCoroutineScope
) : EvoFeature, AutoCloseable {

    private val log = logging("EvoViewModel")

    private val sortedStrategies = strategies.sortedBy { it.name }
    private fun strategyByName(name: String): AudioEvolutionStrategy =
        sortedStrategies.first { it.name == name }

    // Authoritative state lives in these flows. The public stateFlow is a pure
    // projection — there's no scan/reducer, no intent bus. Actions write here
    // directly; init {} collectors observe these and drive side effects.
    private val _selectedStrategyName = MutableStateFlow(sortedStrategies.first().name)
    private val _isEnabled = MutableStateFlow(false)
    private val _knob1 = MutableStateFlow(0.5f)
    private val _knob2 = MutableStateFlow(0.5f)

    override val stateFlow: StateFlow<EvoUiState> = combine(
        _selectedStrategyName,
        _isEnabled,
        _knob1,
        _knob2,
    ) { name, enabled, k1, k2 ->
        EvoUiState(
            selectedStrategy = strategyByName(name),
            strategies = sortedStrategies,
            isEnabled = enabled,
            knob1Value = k1,
            knob2Value = k2,
        )
    }.stateIn(
        scope = scope,
        started = SynthFeature.sharingStrategy,
        initialValue = EvoUiState(
            selectedStrategy = sortedStrategies.first(),
            strategies = sortedStrategies,
            isEnabled = false,
            knob1Value = 0.5f,
            knob2Value = 0.5f,
        ),
    )

    private var evoJob: Job? = null

    override val actions = EvoPanelActions(
        setStrategy = { _selectedStrategyName.value = it.name },
        setEnabled = { _isEnabled.value = it },
        setKnob1 = { _knob1.value = it.coerceIn(0f, 1f) },
        setKnob2 = { _knob2.value = it.coerceIn(0f, 1f) },
    )

    init {
        log.debug { "Initialized with ${sortedStrategies.size} strategies: ${sortedStrategies.map { it.name }}" }

        // MIDI CC bridge: external port changes flow into the UI knob state.
        scope.launch(dispatcherProvider.default) {
            synthController.controlFlow(EvoSymbol.DEPTH.controlId).collect { v ->
                _knob1.value = v.asFloat().coerceIn(0f, 1f)
            }
        }
        scope.launch(dispatcherProvider.default) {
            synthController.controlFlow(EvoSymbol.RATE.controlId).collect { v ->
                _knob2.value = v.asFloat().coerceIn(0f, 1f)
            }
        }

        // Strategy lifecycle: when the selection changes, deactivate the prior
        // strategy, activate the new one, reset knobs to mid-range, and restart
        // the loop if currently running. The construction-time emission is
        // skipped (activeStrategy stays null) to match the prior behavior of
        // not activating any strategy until the user does something.
        scope.launch {
            var activeStrategy: String? = null
            _selectedStrategyName.collect { name ->
                val prev = activeStrategy
                if (prev == name) return@collect
                if (prev != null) {
                    strategyByName(prev).onDeactivate()
                    strategyByName(name).onActivate()
                    _knob1.value = 0.5f
                    _knob2.value = 0.5f
                    log.debug { "Strategy switched: $prev → $name" }
                    if (_isEnabled.value) startEvolutionLoop()
                }
                activeStrategy = name
            }
        }

        // Knob value (or strategy switch) → active strategy parameter.
        // Replaces the old applyToEngine side effect inside the scan reducer.
        scope.launch(dispatcherProvider.default) {
            combine(_selectedStrategyName, _knob1) { name, v -> name to v }
                .collect { (name, v) -> strategyByName(name).setKnob1(v) }
        }
        scope.launch(dispatcherProvider.default) {
            combine(_selectedStrategyName, _knob2) { name, v -> name to v }
                .collect { (name, v) -> strategyByName(name).setKnob2(v) }
        }

        // Enable toggle: evolution loop + media-session activity. .drop(1)
        // skips the construction-time false emission so we don't fire a stale
        // mediaSessionStateManager.setEvoActive(false) at startup.
        scope.launch {
            _isEnabled.drop(1).collect { enabled ->
                mediaSessionStateManager.setEvoActive(enabled)
                val current = strategyByName(_selectedStrategyName.value)
                log.debug { "Evolution ${if (enabled) "enabled" else "disabled"} with ${current.name}" }
                if (enabled) {
                    current.onActivate()
                    startEvolutionLoop()
                } else {
                    stopEvolutionLoop()
                    current.onDeactivate()
                }
            }
        }
    }

    private fun startEvolutionLoop() {
        stopEvolutionLoop()

        log.debug { "Starting evolution loop with ${stateFlow.value.selectedStrategy.name}" }

        evoJob = scope.launch(dispatcherProvider.default) {
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

    override fun close() {
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
            synthFeature<EvoFeature>()
    }
}

