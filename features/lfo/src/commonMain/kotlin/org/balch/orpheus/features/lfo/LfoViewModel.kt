package org.balch.orpheus.features.lfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.LorenzSymbol
import org.balch.orpheus.core.plugin.symbols.PolyLfoSymbol
import kotlin.math.ln
import kotlin.math.pow

@Immutable
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false,
    val shape: Float = 1.0f, // 0.0 = square, 1.0 = triangle
    val rangeMin: Float = 0.0f, // 0..1 UI (maps to -1..+1 in C++)
    val rangeMax: Float = 1.0f, // 0..1 UI (maps to -1..+1 in C++)
    val source: Int = 0, // 0=DuoLFO, 1=PolyLFO, 2=Lorenz
    val polyShape: Float = 0.0f,
    val polyShapeSpread: Float = 0.5f,
    val polySpread: Float = 0.5f,
    val polyCoupling: Float = 0.5f,
    val polyRate: Float = 0.5f,
    val lorenzRate: Float = 0.5f,
    val lorenzBalance: Float = 0.5f,
)

@Immutable
data class LfoPanelActions(
    val setLfoA: (Float) -> Unit,
    val setLfoB: (Float) -> Unit,
    val setMode: (HyperLfoMode) -> Unit,
    val setLink: (Boolean) -> Unit,
    val setShape: (Float) -> Unit,
    val setRangeMin: (Float) -> Unit,
    val setRangeMax: (Float) -> Unit,
    val setSource: (Int) -> Unit,
    val setPolyShape: (Float) -> Unit,
    val setPolyShapeSpread: (Float) -> Unit,
    val setPolySpread: (Float) -> Unit,
    val setPolyCoupling: (Float) -> Unit,
    val setPolyRate: (Float) -> Unit,
    val setLorenzRate: (Float) -> Unit,
    val setLorenzBalance: (Float) -> Unit,
) {
    companion object {
        val EMPTY = LfoPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the LFO panel. */
private sealed interface LfoIntent {
    data class LfoA(val value: Float) : LfoIntent
    data class LfoB(val value: Float) : LfoIntent
    data class Mode(val mode: HyperLfoMode) : LfoIntent
    data class Link(val enabled: Boolean) : LfoIntent
    data class Shape(val value: Float) : LfoIntent
    data class RangeMin(val value: Float) : LfoIntent
    data class RangeMax(val value: Float) : LfoIntent
    data class Source(val value: Int) : LfoIntent
    data class PolyShape(val value: Float) : LfoIntent
    data class PolyShapeSpread(val value: Float) : LfoIntent
    data class PolySpread(val value: Float) : LfoIntent
    data class PolyCoupling(val value: Float) : LfoIntent
    data class PolyRate(val value: Float) : LfoIntent
    data class LorenzRate(val value: Float) : LfoIntent
    data class LorenzBalance(val value: Float) : LfoIntent
}

interface LfoFeature : SynthFeature<LfoUiState, LfoPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.LFO
            override val title = "LFO"

            override val markdown = """
        Modulation source with three selectable modes: Dual LFO (Pong), Poly LFO (Drift), and Lorenz Attractor (Chaos).

        ## Modes
        - **&&**: Dual LFO with AND combination — both LFOs must be high for output.
        - **OFF**: Dual LFO disabled.
        - **||**: Dual LFO with OR combination — either LFO high produces output.
        - **DRIFT**: 4-channel morphing poly LFO with spread and coupling controls.
        - **CHAOS**: Lorenz attractor — chaotic, non-repeating modulation.

        ## Dual LFO Controls (&&/OFF/||)
        - **RATE 1**: Frequency of LFO oscillator A (exponential: 0=off, 1=10Hz).
        - **RATE 2**: Frequency of LFO oscillator B (exponential: 0=off, 1=10Hz).
        - **LINK**: Links both LFO frequencies together.
        - **SHAPE**: Blends between square (0) and triangle (1) waveforms.

        ## Drift Controls (DRIFT mode)
        - **RATE**: Speed of the poly LFO.
        - **SHAPE**: Waveform shape morph.
        - **CPL**: Inter-channel coupling strength.
        - **SPREAD**: Phase spread across 4 channels.
        - **SHP SPR**: Shape spread across 4 channels.

        ## Chaos Controls (CHAOS mode)
        - **RATE**: Speed of Lorenz attractor evolution.
        - **BAL**: Blend between X and Z attractor outputs.

        ## Shared
        - **Attenuator**: Output range trim slider (all modes).
            """.trimIndent()

            override val portControlKeys = mapOf(
                DuoLfoSymbol.FREQ_A.controlId.key to "Frequency of LFO oscillator A",
                DuoLfoSymbol.FREQ_B.controlId.key to "Frequency of LFO oscillator B",
                DuoLfoSymbol.MODE.controlId.key to "Combination mode (Off, AND, OR)",
                DuoLfoSymbol.LINK.controlId.key to "Link both LFO frequencies together",
                DuoLfoSymbol.SHAPE.controlId.key to "Waveform shape blend (0=square, 1=triangle)",
                DuoLfoSymbol.SOURCE.controlId.key to "LFO source (0=DuoLFO, 1=PolyLFO, 2=Lorenz)",
                PolyLfoSymbol.SHAPE.controlId.key to "PolyLFO waveform shape morph",
                PolyLfoSymbol.SHAPE_SPREAD.controlId.key to "PolyLFO shape spread across channels",
                PolyLfoSymbol.SPREAD.controlId.key to "PolyLFO phase spread across channels",
                PolyLfoSymbol.COUPLING.controlId.key to "PolyLFO inter-channel coupling",
                PolyLfoSymbol.RATE.controlId.key to "PolyLFO rate",
                LorenzSymbol.RATE.controlId.key to "Lorenz attractor speed",
                LorenzSymbol.BALANCE.controlId.key to "Lorenz slew/smoothing amount (0=raw, 1=max smooth)",
            )
        }
    }
}

/**
 * ViewModel for the LFO panel.
 *
 * Supports three modulation sources: DuoLFO, PolyLFO, and Lorenz attractor.
 * Knob values (0..1) map exponentially to frequency (0..10 Hz) for DuoLFO.
 */
@Inject
@ClassKey
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class LfoViewModel(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : LfoFeature {

    // ═══════════════════════════════════════════════════════════
    // DuoLfo control flows
    // ═══════════════════════════════════════════════════════════
    private val freqAId = synthController.controlFlow(DuoLfoSymbol.FREQ_A.controlId)
    private val freqBId = synthController.controlFlow(DuoLfoSymbol.FREQ_B.controlId)
    private val modeId = synthController.controlFlow(DuoLfoSymbol.MODE.controlId)
    private val linkId = synthController.controlFlow(DuoLfoSymbol.LINK.controlId)
    private val shapeId = synthController.controlFlow(DuoLfoSymbol.SHAPE.controlId)
    private val rangeMinId = synthController.controlFlow(DuoLfoSymbol.RANGE_MIN.controlId)
    private val rangeMaxId = synthController.controlFlow(DuoLfoSymbol.RANGE_MAX.controlId)
    private val sourceId = synthController.controlFlow(DuoLfoSymbol.SOURCE.controlId)

    // ═══════════════════════════════════════════════════════════
    // PolyLFO control flows
    // ═══════════════════════════════════════════════════════════
    private val polyShapeId = synthController.controlFlow(PolyLfoSymbol.SHAPE.controlId)
    private val polyShapeSpreadId = synthController.controlFlow(PolyLfoSymbol.SHAPE_SPREAD.controlId)
    private val polySpreadId = synthController.controlFlow(PolyLfoSymbol.SPREAD.controlId)
    private val polyCouplingId = synthController.controlFlow(PolyLfoSymbol.COUPLING.controlId)
    private val polyRateId = synthController.controlFlow(PolyLfoSymbol.RATE.controlId)
    private val polyBypassId = synthController.controlFlow(PolyLfoSymbol.BYPASS.controlId)

    // ═══════════════════════════════════════════════════════════
    // Lorenz control flows
    // ═══════════════════════════════════════════════════════════
    private val lorenzRateId = synthController.controlFlow(LorenzSymbol.RATE.controlId)
    private val lorenzBalanceId = synthController.controlFlow(LorenzSymbol.BALANCE.controlId)
    private val lorenzBypassId = synthController.controlFlow(LorenzSymbol.BYPASS.controlId)

    override val actions = LfoPanelActions(
        setLfoA = ::setLfoA,
        setLfoB = ::setLfoB,
        setMode = modeId.enumSetter(),
        setLink = linkId.boolSetter(),
        setShape = shapeId.floatSetter(),
        setRangeMin = rangeMinId.floatSetter(),
        setRangeMax = rangeMaxId.floatSetter(),
        setSource = ::setSource,
        setPolyShape = polyShapeId.floatSetter(),
        setPolyShapeSpread = polyShapeSpreadId.floatSetter(),
        setPolySpread = polySpreadId.floatSetter(),
        setPolyCoupling = polyCouplingId.floatSetter(),
        setPolyRate = polyRateId.floatSetter(),
        setLorenzRate = lorenzRateId.floatSetter(),
        setLorenzBalance = lorenzBalanceId.floatSetter(),
    )

    // Control changes -> LfoIntent
    private val controlIntents = merge(
        freqAId.map { LfoIntent.LfoA(it.asFloat()) },
        freqBId.map { LfoIntent.LfoB(it.asFloat()) },
        modeId.map {
            val modes = HyperLfoMode.entries
            val index = (it.asInt()).coerceIn(0, modes.size - 1)
            LfoIntent.Mode(modes[index])
        },
        linkId.map { LfoIntent.Link(it.asBoolean()) },
        shapeId.map { LfoIntent.Shape(it.asFloat()) },
        rangeMinId.map { LfoIntent.RangeMin(it.asFloat()) },
        rangeMaxId.map { LfoIntent.RangeMax(it.asFloat()) },
        sourceId.map { LfoIntent.Source(it.asInt()) },
        polyShapeId.map { LfoIntent.PolyShape(it.asFloat()) },
        polyShapeSpreadId.map { LfoIntent.PolyShapeSpread(it.asFloat()) },
        polySpreadId.map { LfoIntent.PolySpread(it.asFloat()) },
        polyCouplingId.map { LfoIntent.PolyCoupling(it.asFloat()) },
        polyRateId.map { LfoIntent.PolyRate(it.asFloat()) },
        lorenzRateId.map { LfoIntent.LorenzRate(it.asFloat()) },
        lorenzBalanceId.map { LfoIntent.LorenzBalance(it.asFloat()) },
    )

    override val stateFlow: StateFlow<LfoUiState> =
        controlIntents
            .scan(LfoUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = LfoUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: LfoUiState, intent: LfoIntent): LfoUiState =
        when (intent) {
            is LfoIntent.LfoA -> state.copy(lfoA = freqToKnob(intent.value))
            is LfoIntent.LfoB -> state.copy(lfoB = freqToKnob(intent.value))
            is LfoIntent.Mode -> state.copy(mode = intent.mode)
            is LfoIntent.Link -> state.copy(linkEnabled = intent.enabled)
            is LfoIntent.Shape -> state.copy(shape = intent.value)
            is LfoIntent.RangeMin -> state.copy(rangeMin = intent.value)
            is LfoIntent.RangeMax -> state.copy(rangeMax = intent.value)
            is LfoIntent.Source -> state.copy(source = intent.value)
            is LfoIntent.PolyShape -> state.copy(polyShape = intent.value)
            is LfoIntent.PolyShapeSpread -> state.copy(polyShapeSpread = intent.value)
            is LfoIntent.PolySpread -> state.copy(polySpread = intent.value)
            is LfoIntent.PolyCoupling -> state.copy(polyCoupling = intent.value)
            is LfoIntent.PolyRate -> state.copy(polyRate = intent.value)
            is LfoIntent.LorenzRate -> state.copy(lorenzRate = intent.value)
            is LfoIntent.LorenzBalance -> state.copy(lorenzBalance = intent.value)
        }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setLfoA(value: Float) {
        freqAId.value = FloatValue(knobToFreq(value))
    }

    fun setLfoB(value: Float) {
        freqBId.value = FloatValue(knobToFreq(value))
    }

    /**
     * Set the LFO source and toggle bypass on inactive plugins.
     * source=0 (DuoLFO): PolyLFO bypassed, Lorenz bypassed
     * source=1 (PolyLFO): PolyLFO active, Lorenz bypassed
     * source=2 (Lorenz): PolyLFO bypassed, Lorenz active
     */
    fun setSource(source: Int) {
        sourceId.value = IntValue(source)
        // Toggle bypass on the sub-plugins
        val polyBypassed = source != 1
        val lorenzBypassed = source != 2
        synthController.setPluginControl(
            PolyLfoSymbol.BYPASS.controlId,
            BoolValue(polyBypassed),
            ControlEventOrigin.UI
        )
        synthController.setPluginControl(
            LorenzSymbol.BYPASS.controlId,
            BoolValue(lorenzBypassed),
            ControlEventOrigin.UI
        )
    }

    // ═══════════════════════════════════════════════════════════
    // FREQUENCY MAPPING
    // ═══════════════════════════════════════════════════════════

    companion object {
        // Exponential knob mapping: knob 0..1 -> freq MIN_HZ..MAX_HZ
        // knob=0 -> 0.33 Hz (3s cycle), knob=1 -> 5 Hz
        // Formula: freq = MIN_HZ * (MAX_HZ/MIN_HZ)^knob
        private const val MIN_HZ = 0.01f  // 100-second cycle
        private const val MAX_HZ = 5f
        private val RATIO = MAX_HZ / MIN_HZ
        private val LN_RATIO = ln(RATIO)

        internal fun knobToFreq(knob: Float): Float {
            return MIN_HZ * RATIO.pow(knob)
        }

        internal fun freqToKnob(freq: Float): Float {
            if (freq <= MIN_HZ) return 0f
            return (ln(freq / MIN_HZ) / LN_RATIO).coerceIn(0f, 1f)
        }

        fun previewFeature(state: LfoUiState = LfoUiState()): LfoFeature =
            object : LfoFeature {
                override val stateFlow: StateFlow<LfoUiState> = MutableStateFlow(state)
                override val actions: LfoPanelActions = LfoPanelActions.EMPTY
            }

        @Composable
        fun feature(): LfoFeature =
            synthFeature<LfoViewModel, LfoFeature>()
    }
}
