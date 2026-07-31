package org.balch.orpheus.features.tides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.controller.intSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.symbols.TidesSymbol

@Immutable
data class TidesUiState(
    val frequency: Float = 0.5f,
    val slope: Float = 0.5f,
    val shape: Float = 0.5f,
    val smoothness: Float = 0.5f,
    val shift: Float = 0.5f,  // center position (matches MI hardware knob default)
    val mix: Float = 0.0f,
    val clockOffset: Float = 0.0f,
    val rampMode: Int = 1,       // LOOPING
    val outputMode: Int = 0,     // GATES
    val range: Int = 0,          // CONTROL
    val gateSource: Int = 0,     // Voice Gate
    val clockSource: Int = 0,    // Internal
)

@Immutable
data class TidesPanelActions(
    val setFrequency: (Float) -> Unit,
    val setSlope: (Float) -> Unit,
    val setShape: (Float) -> Unit,
    val setSmoothness: (Float) -> Unit,
    val setShift: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setClockOffset: (Float) -> Unit,
    val setRampMode: (Int) -> Unit,
    val setOutputMode: (Int) -> Unit,
    val setRange: (Int) -> Unit,
    val setGateSource: (Int) -> Unit,
    val setClockSource: (Int) -> Unit,
) {
    companion object {
        val EMPTY = TidesPanelActions(
            setFrequency = {}, setSlope = {}, setShape = {},
            setSmoothness = {}, setShift = {}, setMix = {},
            setClockOffset = {}, setRampMode = {}, setOutputMode = {},
            setRange = {}, setGateSource = {}, setClockSource = {},
        )
    }
}

/** User intents for the Tides (Waves) panel. */
private sealed interface TidesIntent {
    data class Frequency(val value: Float) : TidesIntent
    data class Slope(val value: Float) : TidesIntent
    data class Shape(val value: Float) : TidesIntent
    data class Smoothness(val value: Float) : TidesIntent
    data class Shift(val value: Float) : TidesIntent
    data class Mix(val value: Float) : TidesIntent
    data class ClockOffset(val value: Float) : TidesIntent
    data class RampMode(val value: Int) : TidesIntent
    data class OutputMode(val value: Int) : TidesIntent
    data class Range(val value: Int) : TidesIntent
    data class GateSource(val value: Int) : TidesIntent
    data class ClockSource(val value: Int) : TidesIntent
}

interface TidesFeature : SynthFeature<TidesUiState, TidesPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.TIDES
            override val title = "Waves"

            override val markdown = """
        Waves is a function generator producing evolving ramps and envelopes.
        It can run as an LFO, envelope, or audio-rate oscillator, with four output
        modes and flexible clock / gate routing.

        ## Core Controls
        - **FREQ**: Base frequency / rate of the ramp generator (0=slow, 1=audio rate).
        - **SLOPE**: Attack/decay balance — 0=all attack (rising ramp), 1=all decay (falling).
        - **SHAPE**: Ramp waveshape — 0=linear, 0.5=exponential curve, 1=sinusoidal.
        - **SMOOTH**: Output smoothness / slew. 0=hard edges, 1=fully smoothed.
        - **SHIFT**: Shifts all four outputs by a fraction of the cycle.
        - **MIX**: Output level — 0=silent, 1=full amplitude.

        ## Clock & Sync
        - **CLK OFFSET**: Fine phase offset when slaved to an external clock.
        - **CLK SOURCE**: INT = free-running internal clock; TEMPO = tempo-synced external clock.

        ## Modes
        - **RAMP MODE**: AD = one-shot envelope; LOOP = continuous LFO; AR = looping with re-trigger.
        - **OUTPUT MODE**: GATES = gate signals; AMPLITUDE = unipolar ramp; PHASE = bipolar ramp; FREQUENCY = audio-rate FM signal.
        - **RANGE**: CONTROL = CV / LFO rates (0.001–10 Hz); AUDIO = audio oscillator rates (20–15 kHz).

        ## Routing
        - **GATE SOURCE**: Which voice gate triggers the envelope (Voice, T1–T3, Free).
            """.trimIndent()

            override val portControlKeys = mapOf(
                TidesSymbol.FREQUENCY.controlId.key to "Base frequency / rate of the function generator",
                TidesSymbol.SLOPE.controlId.key to "Attack–decay slope balance (0=all attack, 1=all decay)",
                TidesSymbol.SHAPE.controlId.key to "Ramp waveshape (0=linear, 0.5=exponential, 1=sine)",
                TidesSymbol.SMOOTHNESS.controlId.key to "Output smoothness / slew (0=hard, 1=smooth)",
                TidesSymbol.SHIFT.controlId.key to "Phase shift applied to all outputs",
                TidesSymbol.MIX.controlId.key to "Output level (0=off, 1=full)",
                TidesSymbol.CLOCK_OFFSET.controlId.key to "Fine phase offset for external clock sync",
                TidesSymbol.RAMP_MODE.controlId.key to "Ramp mode (0=AD, 1=LOOP, 2=AR)",
                TidesSymbol.OUTPUT_MODE.controlId.key to "Output mode (0=Gates, 1=Amplitude, 2=Phase, 3=Frequency)",
                TidesSymbol.RANGE.controlId.key to "Frequency range (0=Control/LFO, 1=Audio)",
                TidesSymbol.GATE_SOURCE.controlId.key to "Gate / trigger source (0=Voice, 1=T1, 2=T2, 3=T3, 4=Free)",
                TidesSymbol.CLOCK_SOURCE.controlId.key to "Clock source (0=Internal, 1=Tempo)",
            )
        }
    }
}

/**
 * ViewModel for the Waves (Tides) function generator panel.
 *
 * Supports AD / looping / AR ramp modes with four output modes and flexible
 * clock/gate routing.
 */
@Inject
@SynthFeatureKey(TidesFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class TidesViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : TidesFeature {

    // Control flows
    private val frequencyId = synthController.controlFlow(TidesSymbol.FREQUENCY.controlId)
    private val slopeId = synthController.controlFlow(TidesSymbol.SLOPE.controlId)
    private val shapeId = synthController.controlFlow(TidesSymbol.SHAPE.controlId)
    private val smoothnessId = synthController.controlFlow(TidesSymbol.SMOOTHNESS.controlId)
    private val shiftId = synthController.controlFlow(TidesSymbol.SHIFT.controlId)
    private val mixId = synthController.controlFlow(TidesSymbol.MIX.controlId)
    private val clockOffsetId = synthController.controlFlow(TidesSymbol.CLOCK_OFFSET.controlId)
    private val rampModeId = synthController.controlFlow(TidesSymbol.RAMP_MODE.controlId)
    private val outputModeId = synthController.controlFlow(TidesSymbol.OUTPUT_MODE.controlId)
    private val rangeId = synthController.controlFlow(TidesSymbol.RANGE.controlId)
    private val gateSourceId = synthController.controlFlow(TidesSymbol.GATE_SOURCE.controlId)
    private val clockSourceId = synthController.controlFlow(TidesSymbol.CLOCK_SOURCE.controlId)

    override val actions = TidesPanelActions(
        setFrequency = frequencyId.floatSetter(),
        setSlope = slopeId.floatSetter(),
        setShape = shapeId.floatSetter(),
        setSmoothness = smoothnessId.floatSetter(),
        setShift = shiftId.floatSetter(),
        setMix = mixId.floatSetter(),
        setClockOffset = clockOffsetId.floatSetter(),
        setRampMode = rampModeId.intSetter(),
        setOutputMode = outputModeId.intSetter(),
        setRange = rangeId.intSetter(),
        setGateSource = gateSourceId.intSetter(),
        setClockSource = clockSourceId.intSetter(),
    )

    // Control changes -> TidesIntent
    private val controlIntents = merge(
        frequencyId.map { TidesIntent.Frequency(it.asFloat()) },
        slopeId.map { TidesIntent.Slope(it.asFloat()) },
        shapeId.map { TidesIntent.Shape(it.asFloat()) },
        smoothnessId.map { TidesIntent.Smoothness(it.asFloat()) },
        shiftId.map { TidesIntent.Shift(it.asFloat()) },
        mixId.map { TidesIntent.Mix(it.asFloat()) },
        clockOffsetId.map { TidesIntent.ClockOffset(it.asFloat()) },
        rampModeId.map { TidesIntent.RampMode(it.asInt()) },
        outputModeId.map { TidesIntent.OutputMode(it.asInt()) },
        rangeId.map { TidesIntent.Range(it.asInt()) },
        gateSourceId.map { TidesIntent.GateSource(it.asInt()) },
        clockSourceId.map { TidesIntent.ClockSource(it.asInt()) },
    )

    override val stateFlow: StateFlow<TidesUiState> =
        controlIntents
            .scan(TidesUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = SynthFeature.sharingStrategy,
                initialValue = TidesUiState()
            )

    private fun reduce(state: TidesUiState, intent: TidesIntent): TidesUiState = when (intent) {
        is TidesIntent.Frequency -> state.copy(frequency = intent.value)
        is TidesIntent.Slope -> state.copy(slope = intent.value)
        is TidesIntent.Shape -> state.copy(shape = intent.value)
        is TidesIntent.Smoothness -> state.copy(smoothness = intent.value)
        is TidesIntent.Shift -> state.copy(shift = intent.value)
        is TidesIntent.Mix -> state.copy(mix = intent.value)
        is TidesIntent.ClockOffset -> state.copy(clockOffset = intent.value)
        is TidesIntent.RampMode -> state.copy(rampMode = intent.value)
        is TidesIntent.OutputMode -> state.copy(outputMode = intent.value)
        is TidesIntent.Range -> state.copy(range = intent.value)
        is TidesIntent.GateSource -> state.copy(gateSource = intent.value)
        is TidesIntent.ClockSource -> state.copy(clockSource = intent.value)
    }

    companion object {

        fun previewFeature(state: TidesUiState = TidesUiState()): TidesFeature =
            object : TidesFeature {
                override val stateFlow: StateFlow<TidesUiState> = MutableStateFlow(state)
                override val actions: TidesPanelActions = TidesPanelActions.EMPTY
            }

        @Composable
        fun feature(): TidesFeature = synthFeature<TidesFeature>()
    }
}
