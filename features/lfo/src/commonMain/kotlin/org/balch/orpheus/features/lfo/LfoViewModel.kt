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
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import kotlin.math.ln
import kotlin.math.pow

@Immutable
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false,
    val shape: Float = 1.0f // 0.0 = square, 1.0 = triangle
)

@Immutable
data class LfoPanelActions(
    val setLfoA: (Float) -> Unit,
    val setLfoB: (Float) -> Unit,
    val setMode: (HyperLfoMode) -> Unit,
    val setLink: (Boolean) -> Unit,
    val setShape: (Float) -> Unit
) {
    companion object {
        val EMPTY = LfoPanelActions({}, {}, {}, {}, {})
    }
}

/** User intents for the LFO panel. */
private sealed interface LfoIntent {
    data class LfoA(val value: Float) : LfoIntent
    data class LfoB(val value: Float) : LfoIntent
    data class Mode(val mode: HyperLfoMode) : LfoIntent
    data class Link(val enabled: Boolean) : LfoIntent
    data class Shape(val value: Float) : LfoIntent
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
        Dual LFO with two oscillators (A & B) and logical AND/OR combination modes. Controls frequency of each LFO, combination mode, link, and triangle mode.

        ## Controls
        - **LFO A**: Frequency of LFO oscillator A (exponential: 0=off, 1=10Hz).
        - **LFO B**: Frequency of LFO oscillator B (exponential: 0=off, 1=10Hz).

        ## Switches
        - **MODE**: Combination mode for the two LFOs (Off, AND, OR, XOR).
        - **LINK**: Links both LFO frequencies together.
        - **SHAPE**: Blends between square (0) and triangle (1) waveforms.

        ## Tips
        - Use LINK to keep both LFOs at the same frequency while adjusting a single knob.
        - Combine AND/OR modes for complex modulation shapes from two simple LFOs.
        - Use SHAPE to morph between sharp square edges and smooth triangle curves.
        - Low knob values give very slow sweeps (0.1 = ~0.01Hz / 100s cycle).
            """.trimIndent()

            override val portControlKeys = mapOf(
                DuoLfoSymbol.FREQ_A.controlId.key to "Frequency of LFO oscillator A",
                DuoLfoSymbol.FREQ_B.controlId.key to "Frequency of LFO oscillator B",
                DuoLfoSymbol.MODE.controlId.key to "Combination mode (Off, AND, OR, XOR)",
                DuoLfoSymbol.LINK.controlId.key to "Link both LFO frequencies together",
                DuoLfoSymbol.SHAPE.controlId.key to "Waveform shape blend (0=square, 1=triangle)",
            )
        }
    }
}

/**
 * ViewModel for the Hyper LFO panel.
 *
 * Knob values (0..1) map exponentially to frequency (0..10 Hz).
 * Most of the knob range is dedicated to slow rates for sweeping modulation.
 */
@Inject
@ClassKey(LfoViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class LfoViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : LfoFeature {

    // Control flows for DuoLfo plugin (using plugin-api symbols)
    private val freqAId = synthController.controlFlow(DuoLfoSymbol.FREQ_A.controlId)
    private val freqBId = synthController.controlFlow(DuoLfoSymbol.FREQ_B.controlId)
    private val modeId = synthController.controlFlow(DuoLfoSymbol.MODE.controlId)
    private val linkId = synthController.controlFlow(DuoLfoSymbol.LINK.controlId)
    private val shapeId = synthController.controlFlow(DuoLfoSymbol.SHAPE.controlId)

    override val actions = LfoPanelActions(
        setLfoA = ::setLfoA,
        setLfoB = ::setLfoB,
        setMode = modeId.enumSetter(),
        setLink = linkId.boolSetter(),
        setShape = shapeId.floatSetter()
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
        shapeId.map { LfoIntent.Shape(it.asFloat()) }
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

    // ═══════════════════════════════════════════════════════════
    // FREQUENCY MAPPING
    // ═══════════════════════════════════════════════════════════

    companion object {
        // Exponential knob mapping: knob 0..1 → freq MIN_HZ..MAX_HZ
        // knob=0 → 0.33 Hz (3s cycle), knob=1 → 5 Hz
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
