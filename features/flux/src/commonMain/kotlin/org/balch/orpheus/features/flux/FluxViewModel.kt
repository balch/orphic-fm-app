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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
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

@Inject
@ViewModelKey(FluxViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FluxViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    private val presetLoader: org.balch.orpheus.core.presets.PresetLoader,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), FluxFeature {

    override val actions = FluxPanelActions(
        setSpread = ::setSpread,
        setBias = ::setBias,
        setSteps = ::setSteps,
        setDejaVu = ::setDejaVu,
        setLength = ::setLength,
        setScale = ::setScale,
        setRate = ::setRate,
        setJitter = ::setJitter,
        setProbability = ::setProbability,
        setClockSource = ::setClockSource,
        setGateLength = ::setGateLength
    )

    private val _userIntents = MutableSharedFlow<FluxIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> FluxIntent.Restore
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

    // Map controller events to intents
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.FLUX_SPREAD -> FluxIntent.Spread(event.value, fromSequencer)
            ControlIds.FLUX_BIAS -> FluxIntent.Bias(event.value, fromSequencer)
            ControlIds.FLUX_STEPS -> FluxIntent.Steps(event.value, fromSequencer)
            ControlIds.FLUX_DEJA_VU -> FluxIntent.DejaVu(event.value, fromSequencer)
            ControlIds.FLUX_LENGTH -> FluxIntent.Length(event.value.toInt())
            ControlIds.FLUX_SCALE -> FluxIntent.Scale(event.value.toInt())
            ControlIds.FLUX_RATE -> FluxIntent.Rate(event.value)
            ControlIds.FLUX_JITTER -> FluxIntent.Jitter(event.value)
            ControlIds.FLUX_PROBABILITY -> FluxIntent.Probability(event.value)
            ControlIds.FLUX_CLOCK_SOURCE -> FluxIntent.ClockSource(event.value.toInt())
            ControlIds.FLUX_GATE_LENGTH -> FluxIntent.GateLength(event.value)
            else -> null
        }
    }

    override val stateFlow: StateFlow<FluxUiState> =
        merge(_userIntents, presetIntents, controlIntents)
            .scan(FluxUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = FluxUiState()
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

    private fun applyToEngine(state: FluxUiState, intent: FluxIntent) {
        when (intent) {
            is FluxIntent.Spread -> if (!intent.fromSequencer) engine.setFluxSpread(intent.value)
            is FluxIntent.Bias -> if (!intent.fromSequencer) engine.setFluxBias(intent.value)
            is FluxIntent.Steps -> if (!intent.fromSequencer) engine.setFluxSteps(intent.value)
            is FluxIntent.DejaVu -> if (!intent.fromSequencer) engine.setFluxDejaVu(intent.value)
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

    fun setSpread(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(FluxIntent.Spread(value, fromSequencer))
        synthController.emitControlChange(ControlIds.FLUX_SPREAD, value, ControlEventOrigin.UI)
    }

    fun setBias(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(FluxIntent.Bias(value, fromSequencer))
        synthController.emitControlChange(ControlIds.FLUX_BIAS, value, ControlEventOrigin.UI)
    }

    fun setSteps(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(FluxIntent.Steps(value, fromSequencer))
        synthController.emitControlChange(ControlIds.FLUX_STEPS, value, ControlEventOrigin.UI)
    }

    fun setDejaVu(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(FluxIntent.DejaVu(value, fromSequencer))
        synthController.emitControlChange(ControlIds.FLUX_DEJA_VU, value, ControlEventOrigin.UI)
    }

    fun setLength(value: Int) {
        _userIntents.tryEmit(FluxIntent.Length(value))
        synthController.emitControlChange(ControlIds.FLUX_LENGTH, value.toFloat(), ControlEventOrigin.UI)
    }

    fun setScale(value: Int) {
        _userIntents.tryEmit(FluxIntent.Scale(value))
        synthController.emitControlChange(ControlIds.FLUX_SCALE, value.toFloat(), ControlEventOrigin.UI)
    }

    fun setRate(value: Float) {
        _userIntents.tryEmit(FluxIntent.Rate(value))
        synthController.emitControlChange(ControlIds.FLUX_RATE, value, ControlEventOrigin.UI)
    }

    fun setJitter(value: Float) {
        _userIntents.tryEmit(FluxIntent.Jitter(value))
        synthController.emitControlChange(ControlIds.FLUX_JITTER, value, ControlEventOrigin.UI)
    }

    fun setProbability(value: Float) {
        _userIntents.tryEmit(FluxIntent.Probability(value))
        synthController.emitControlChange(ControlIds.FLUX_PROBABILITY, value, ControlEventOrigin.UI)
    }

    fun setClockSource(value: Int) {
        _userIntents.tryEmit(FluxIntent.ClockSource(value))
        synthController.emitControlChange(ControlIds.FLUX_CLOCK_SOURCE, value.toFloat(), ControlEventOrigin.UI)
    }

    fun setGateLength(value: Float) {
        _userIntents.tryEmit(FluxIntent.GateLength(value))
        synthController.emitControlChange(ControlIds.FLUX_GATE_LENGTH, value, ControlEventOrigin.UI)
    }

    companion object {

        fun previewFeature(state: FluxUiState = FluxUiState()): FluxFeature =
            object : FluxFeature {
                override val stateFlow: StateFlow<FluxUiState> = MutableStateFlow(state)
                override val actions: FluxPanelActions = FluxPanelActions.EMPTY
            }

        @Composable
        fun feature(): FluxFeature = synthViewModel<FluxViewModel, FluxFeature>()
    }
}
