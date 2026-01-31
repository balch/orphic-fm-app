package org.balch.orpheus.features.flux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

@Immutable
data class FluxUiState(
    val spread: Float = 0.5f,
    val bias: Float = 0.5f,
    val steps: Float = 0.5f,
    val dejaVu: Float = 0.0f,
    val length: Int = 8,
    val scaleIndex: Int = 0,
    val rate: Float = 0.5f,
    val jitter: Float = 0.0f,
    val probability: Float = 0.5f,
    val clockSource: Int = 0, // 0=Internal, 1=LFO
    val gateLength: Float = 0.5f
)

@Immutable
data class FluxPanelActions(
    val setSpread: (Float) -> Unit,
    val setBias: (Float) -> Unit,
    val setSteps: (Float) -> Unit,
    val setDejaVu: (Float) -> Unit,
    val setLength: (Int) -> Unit,
    val setScale: (Int) -> Unit,
    val setRate: (Float) -> Unit,
    val setJitter: (Float) -> Unit,
    val setProbability: (Float) -> Unit,
    val setClockSource: (Int) -> Unit,
    val setGateLength: (Float) -> Unit
) {
    companion object {
        val EMPTY = FluxPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private sealed interface FluxIntent {
    data class Spread(val value: Float, val fromSequencer: Boolean = false) : FluxIntent
    data class Bias(val value: Float, val fromSequencer: Boolean = false) : FluxIntent
    data class Steps(val value: Float, val fromSequencer: Boolean = false) : FluxIntent
    data class DejaVu(val value: Float, val fromSequencer: Boolean = false) : FluxIntent
    data class Length(val value: Int) : FluxIntent
    data class Scale(val value: Int) : FluxIntent
    data class Rate(val value: Float) : FluxIntent
    data class Jitter(val value: Float) : FluxIntent
    data class Probability(val value: Float) : FluxIntent
    data class ClockSource(val value: Int) : FluxIntent
    data class GateLength(val value: Float) : FluxIntent
    data class Restore(val state: FluxUiState) : FluxIntent
}

typealias FluxFeature = SynthFeature<FluxUiState, FluxPanelActions>

@ViewModelKey(FluxViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FluxViewModel @Inject constructor(
    private val engine: SynthEngine,
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    presetLoader: PresetLoader
) : ViewModel(), FluxFeature {

    // ... actions ... (keep as is, but I can't overwrite easily without seeing lines again if I'm not careful. 
    // Wait, I can't just replace the constructor without replacing the whole class header.
    
    override val actions = FluxPanelActions(
        setSpread = ::onSpreadChange,
        setBias = ::onBiasChange,
        setSteps = ::onStepsChange,
        setDejaVu = ::onDejaVuChange,
        setLength = ::onLengthChange,
        setScale = ::onScaleChange,
        setRate = ::onRateChange,
        setJitter = ::onJitterChange,
        setProbability = ::onProbabilityChange,
        setClockSource = ::onClockSourceChange,
        setGateLength = ::onGateLengthChange
    )

    private val _userIntents = MutableSharedFlow<FluxIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Map controller events to intents
    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            Ids.SPREAD -> FluxIntent.Spread(event.value, fromSequencer)
            Ids.BIAS -> FluxIntent.Bias(event.value, fromSequencer)
            Ids.STEPS -> FluxIntent.Steps(event.value, fromSequencer)
            Ids.DEJA_VU -> FluxIntent.DejaVu(event.value, fromSequencer)
            else -> null
        }
    }
    
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        FluxIntent.Restore(
            FluxUiState(
                spread = preset.fluxSpread,
                bias = preset.fluxBias,
                steps = preset.fluxSteps,
                dejaVu = preset.fluxDejaVu,
                length = preset.fluxLength,
                scaleIndex = preset.fluxScale,
                rate = preset.fluxRate,
                jitter = preset.fluxJitter,
                probability = preset.fluxProbability,
                clockSource = preset.fluxClockSource,
                gateLength = preset.fluxGateLength
            )
        )
    }

    override val stateFlow: StateFlow<FluxUiState> = flow {
        val initial = loadInitialState()
        applyFullState(initial)
        emit(initial)
        
        emitAll(
            merge(_userIntents, controlIntents, presetIntents)
                .onEach { intent -> if (intent != null) applyToEngine(intent) }
                .scan(initial) { state, intent -> if (intent != null) reduce(state, intent) else state }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FluxUiState()
    )

    private fun loadInitialState(): FluxUiState = FluxUiState(
        spread = engine.getFluxSpread(),
        bias = engine.getFluxBias(),
        steps = engine.getFluxSteps(),
        dejaVu = engine.getFluxDejaVu(),
        length = engine.getFluxLength(),
        scaleIndex = engine.getFluxScale(),
        rate = engine.getFluxRate(),
        jitter = engine.getFluxJitter(),
        probability = engine.getFluxProbability(),
        clockSource = engine.getFluxClockSource()
    )

    private fun reduce(state: FluxUiState, intent: FluxIntent): FluxUiState = when (intent) {
        is FluxIntent.Spread -> state.copy(spread = intent.value)
        is FluxIntent.Bias -> state.copy(bias = intent.value)
        is FluxIntent.Steps -> state.copy(steps = intent.value)
        is FluxIntent.DejaVu -> state.copy(dejaVu = intent.value)
        is FluxIntent.Length -> state.copy(length = intent.value)
        is FluxIntent.Scale -> state.copy(scaleIndex = intent.value)
        is FluxIntent.Rate -> state.copy(rate = intent.value)
        is FluxIntent.Jitter -> state.copy(jitter = intent.value)
        is FluxIntent.Probability -> state.copy(probability = intent.value)
        is FluxIntent.ClockSource -> state.copy(clockSource = intent.value)
        is FluxIntent.GateLength -> state.copy(gateLength = intent.value)
        is FluxIntent.Restore -> intent.state
    }

    private fun applyToEngine(intent: FluxIntent) {
        when (intent) {
            is FluxIntent.Spread -> engine.setFluxSpread(intent.value)
            is FluxIntent.Bias -> engine.setFluxBias(intent.value)
            is FluxIntent.Steps -> engine.setFluxSteps(intent.value)
            is FluxIntent.DejaVu -> engine.setFluxDejaVu(intent.value)
            is FluxIntent.Length -> engine.setFluxLength(intent.value)
            is FluxIntent.Scale -> engine.setFluxScale(intent.value)
            is FluxIntent.Rate -> engine.setFluxRate(intent.value)
            is FluxIntent.Jitter -> engine.setFluxJitter(intent.value)
            is FluxIntent.Probability -> engine.setFluxProbability(intent.value)
            is FluxIntent.ClockSource -> engine.setFluxClockSource(intent.value)
            is FluxIntent.GateLength -> engine.setFluxGateLength(intent.value)
            is FluxIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: FluxUiState) {
        engine.setFluxSpread(state.spread)
        engine.setFluxBias(state.bias)
        engine.setFluxSteps(state.steps)
        engine.setFluxDejaVu(state.dejaVu)
        engine.setFluxLength(state.length)
        engine.setFluxScale(state.scaleIndex)
        engine.setFluxRate(state.rate)
        engine.setFluxJitter(state.jitter)
        engine.setFluxProbability(state.probability)
        engine.setFluxClockSource(state.clockSource)
        engine.setFluxGateLength(state.gateLength)
    }

    fun onSpreadChange(value: Float) = _userIntents.tryEmit(FluxIntent.Spread(value))
    fun onBiasChange(value: Float) = _userIntents.tryEmit(FluxIntent.Bias(value))
    fun onStepsChange(value: Float) = _userIntents.tryEmit(FluxIntent.Steps(value))
    fun onDejaVuChange(value: Float) = _userIntents.tryEmit(FluxIntent.DejaVu(value))
    fun onLengthChange(value: Int) = _userIntents.tryEmit(FluxIntent.Length(value))
    fun onScaleChange(value: Int) = _userIntents.tryEmit(FluxIntent.Scale(value))
    fun onRateChange(value: Float) = _userIntents.tryEmit(FluxIntent.Rate(value))
    fun onJitterChange(value: Float) = _userIntents.tryEmit(FluxIntent.Jitter(value))
    fun onProbabilityChange(value: Float) = _userIntents.tryEmit(FluxIntent.Probability(value))
    fun onClockSourceChange(value: Int) = _userIntents.tryEmit(FluxIntent.ClockSource(value))
    fun onGateLengthChange(value: Float) = _userIntents.tryEmit(FluxIntent.GateLength(value))

    companion object Ids {
        const val SPREAD = "flux_spread"
        const val BIAS = "flux_bias"
        const val STEPS = "flux_steps"
        const val DEJA_VU = "flux_deja_vu"

        fun previewFeature(state: FluxUiState = FluxUiState()): FluxFeature =
            object : FluxFeature {
                override val stateFlow: StateFlow<FluxUiState> = MutableStateFlow(state)
                override val actions: FluxPanelActions = FluxPanelActions.EMPTY
            }

        @Composable
        fun feature(): FluxFeature = synthViewModel<FluxViewModel, FluxFeature>()
    }
}
