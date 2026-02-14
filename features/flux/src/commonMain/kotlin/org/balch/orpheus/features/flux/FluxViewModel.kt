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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.FluxSymbol
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
    val clockSource: Int = 0,
    val gateLength: Float = 0.5f,
    val tModel: Int = 0,
    val tRange: Int = 1,
    val pulseWidth: Float = 0.5f,
    val pulseWidthStd: Float = 0.0f,
    val controlMode: Int = 0,
    val voltageRange: Int = 2,
    val mix: Float = 0.0f
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
    val setGateLength: (Float) -> Unit,
    val setTModel: (Int) -> Unit,
    val setTRange: (Int) -> Unit,
    val setPulseWidth: (Float) -> Unit,
    val setPulseWidthStd: (Float) -> Unit,
    val setControlMode: (Int) -> Unit,
    val setVoltageRange: (Int) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object {
        val EMPTY = FluxPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private sealed interface FluxIntent {
    data class Spread(val value: Float) : FluxIntent
    data class Bias(val value: Float) : FluxIntent
    data class Steps(val value: Float) : FluxIntent
    data class DejaVu(val value: Float) : FluxIntent
    data class Length(val value: Int) : FluxIntent
    data class Scale(val value: Int) : FluxIntent
    data class Rate(val value: Float) : FluxIntent
    data class Jitter(val value: Float) : FluxIntent
    data class Probability(val value: Float) : FluxIntent
    data class ClockSource(val value: Int) : FluxIntent
    data class GateLength(val value: Float) : FluxIntent
    data class TModel(val value: Int) : FluxIntent
    data class TRange(val value: Int) : FluxIntent
    data class PulseWidth(val value: Float) : FluxIntent
    data class PulseWidthStd(val value: Float) : FluxIntent
    data class ControlMode(val value: Int) : FluxIntent
    data class VoltageRange(val value: Int) : FluxIntent
    data class Mix(val value: Float) : FluxIntent
}

typealias FluxFeature = SynthFeature<FluxUiState, FluxPanelActions>

/**
 * ViewModel for the Flux Quantized Random Sequencer panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@ViewModelKey(FluxViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FluxViewModel @Inject constructor(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), FluxFeature {

    // Control flows for Flux plugin ports
    private val spreadId = synthController.controlFlow(FluxSymbol.SPREAD.controlId)
    private val biasId = synthController.controlFlow(FluxSymbol.BIAS.controlId)
    private val stepsId = synthController.controlFlow(FluxSymbol.STEPS.controlId)
    private val dejaVuId = synthController.controlFlow(FluxSymbol.DEJAVU.controlId)
    private val lengthId = synthController.controlFlow(FluxSymbol.LENGTH.controlId)
    private val scaleId = synthController.controlFlow(FluxSymbol.SCALE.controlId)
    private val rateId = synthController.controlFlow(FluxSymbol.RATE.controlId)
    private val jitterId = synthController.controlFlow(FluxSymbol.JITTER.controlId)
    private val probabilityId = synthController.controlFlow(FluxSymbol.PROBABILITY.controlId)
    private val clockSourceId = synthController.controlFlow(FluxSymbol.CLOCK_SOURCE.controlId)
    private val gateLengthId = synthController.controlFlow(FluxSymbol.GATE_LENGTH.controlId)
    private val tModelId = synthController.controlFlow(FluxSymbol.T_MODEL.controlId)
    private val tRangeId = synthController.controlFlow(FluxSymbol.T_RANGE.controlId)
    private val pulseWidthId = synthController.controlFlow(FluxSymbol.PULSE_WIDTH.controlId)
    private val pulseWidthStdId = synthController.controlFlow(FluxSymbol.PULSE_WIDTH_STD.controlId)
    private val controlModeId = synthController.controlFlow(FluxSymbol.CONTROL_MODE.controlId)
    private val voltageRangeId = synthController.controlFlow(FluxSymbol.VOLTAGE_RANGE.controlId)
    private val mixId = synthController.controlFlow(FluxSymbol.MIX.controlId)

    override val actions = FluxPanelActions(
        setSpread = spreadId.floatSetter(),
        setBias = biasId.floatSetter(),
        setSteps = stepsId.floatSetter(),
        setDejaVu = dejaVuId.floatSetter(),
        setLength = lengthId.intSetter(),
        setScale = scaleId.intSetter(),
        setRate = rateId.floatSetter(),
        setJitter = jitterId.floatSetter(),
        setProbability = probabilityId.floatSetter(),
        setClockSource = clockSourceId.intSetter(),
        setGateLength = gateLengthId.floatSetter(),
        setTModel = tModelId.intSetter(),
        setTRange = tRangeId.intSetter(),
        setPulseWidth = pulseWidthId.floatSetter(),
        setPulseWidthStd = pulseWidthStdId.floatSetter(),
        setControlMode = controlModeId.intSetter(),
        setVoltageRange = voltageRangeId.intSetter(),
        setMix = mixId.floatSetter()
    )

    // Control changes -> FluxIntent
    private val controlIntents = merge(
        spreadId.map { FluxIntent.Spread(it.asFloat()) },
        biasId.map { FluxIntent.Bias(it.asFloat()) },
        stepsId.map { FluxIntent.Steps(it.asFloat()) },
        dejaVuId.map { FluxIntent.DejaVu(it.asFloat()) },
        lengthId.map { FluxIntent.Length(it.asInt()) },
        scaleId.map { FluxIntent.Scale(it.asInt()) },
        rateId.map { FluxIntent.Rate(it.asFloat()) },
        jitterId.map { FluxIntent.Jitter(it.asFloat()) },
        probabilityId.map { FluxIntent.Probability(it.asFloat()) },
        clockSourceId.map { FluxIntent.ClockSource(it.asInt()) },
        gateLengthId.map { FluxIntent.GateLength(it.asFloat()) },
        tModelId.map { FluxIntent.TModel(it.asInt()) },
        tRangeId.map { FluxIntent.TRange(it.asInt()) },
        pulseWidthId.map { FluxIntent.PulseWidth(it.asFloat()) },
        pulseWidthStdId.map { FluxIntent.PulseWidthStd(it.asFloat()) },
        controlModeId.map { FluxIntent.ControlMode(it.asInt()) },
        voltageRangeId.map { FluxIntent.VoltageRange(it.asInt()) },
        mixId.map { FluxIntent.Mix(it.asFloat()) }
    )

    override val stateFlow: StateFlow<FluxUiState> =
        controlIntents
            .scan(FluxUiState()) { state, intent ->
                reduce(state, intent)
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
        is FluxIntent.TModel -> state.copy(tModel = intent.value)
        is FluxIntent.TRange -> state.copy(tRange = intent.value)
        is FluxIntent.PulseWidth -> state.copy(pulseWidth = intent.value)
        is FluxIntent.PulseWidthStd -> state.copy(pulseWidthStd = intent.value)
        is FluxIntent.ControlMode -> state.copy(controlMode = intent.value)
        is FluxIntent.VoltageRange -> state.copy(voltageRange = intent.value)
        is FluxIntent.Mix -> state.copy(mix = intent.value)
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
