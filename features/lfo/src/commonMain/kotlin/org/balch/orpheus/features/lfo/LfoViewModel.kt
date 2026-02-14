package org.balch.orpheus.features.lfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.synthViewModel

@Immutable
data class LfoUiState(
    val lfoA: Float = 0.0f,
    val lfoB: Float = 0.0f,
    val lfoAMultiplier: Float = 1.0f, // 0.0 (Fast) -> 1.0 (Slow)
    val lfoBMultiplier: Float = 1.0f, // 0.0 (Fast) -> 1.0 (Slow)
    val mode: HyperLfoMode = HyperLfoMode.OFF,
    val linkEnabled: Boolean = false
)

@Immutable
data class LfoPanelActions(
    val setLfoA: (Float) -> Unit,
    val setLfoB: (Float) -> Unit,
    val setLfoAMultiplier: (Float) -> Unit,
    val setLfoBMultiplier: (Float) -> Unit,
    val setMode: (HyperLfoMode) -> Unit,
    val setLink: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = LfoPanelActions({}, {}, {}, {}, {}, {})
    }
}

/** User intents for the LFO panel. */
private sealed interface LfoIntent {
    data class LfoA(val value: Float) : LfoIntent
    data class LfoB(val value: Float) : LfoIntent
    data class LfoAMultiplier(val value: Float) : LfoIntent
    data class LfoBMultiplier(val value: Float) : LfoIntent
    data class Mode(val mode: HyperLfoMode) : LfoIntent
    data class Link(val enabled: Boolean) : LfoIntent
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
        - **LFO A**: Frequency of LFO oscillator A.
        - **LFO B**: Frequency of LFO oscillator B.

        ## Switches
        - **MODE**: Combination mode for the two LFOs (Off, AND, OR, XOR).
        - **LINK**: Links both LFO frequencies together.
        - **TRIANGLE**: Selects triangle wave mode for the LFO shape.

        ## Tips
        - Use LINK to keep both LFOs at the same frequency while adjusting a single knob.
        - Combine AND/OR modes for complex modulation shapes from two simple LFOs.
            """.trimIndent()

            override val portControlKeys = mapOf(
                DuoLfoSymbol.FREQ_A.controlId.key to "Frequency of LFO oscillator A",
                DuoLfoSymbol.FREQ_B.controlId.key to "Frequency of LFO oscillator B",
                DuoLfoSymbol.MODE.controlId.key to "Combination mode (Off, AND, OR, XOR)",
                DuoLfoSymbol.LINK.controlId.key to "Link both LFO frequencies together",
                DuoLfoSymbol.TRIANGLE_MODE.controlId.key to "Triangle wave mode selection",
            )
        }
    }
}

/**
 * ViewModel for the Hyper LFO panel.
 *
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 * Uses SynthController.controlFlow() for all engine interactions.
 */
@Inject
@ViewModelKey(LfoViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*, *>>())
class LfoViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), LfoFeature {

    // Control flows for DuoLfo plugin (using plugin-api symbols)
    private val freqAId = synthController.controlFlow(DuoLfoSymbol.FREQ_A.controlId)
    private val freqBId = synthController.controlFlow(DuoLfoSymbol.FREQ_B.controlId)
    private val modeId = synthController.controlFlow(DuoLfoSymbol.MODE.controlId)
    private val linkId = synthController.controlFlow(DuoLfoSymbol.LINK.controlId)

    override val actions = LfoPanelActions(
        setLfoA = ::setLfoA,
        setLfoB = ::setLfoB,
        setLfoAMultiplier = ::setLfoAMultiplier,
        setLfoBMultiplier = ::setLfoBMultiplier,
        setMode = modeId.enumSetter(),
        setLink = linkId.boolSetter()
    )

    // Local intents for UI-only state (multipliers)
    private val uiIntents = MutableSharedFlow<LfoIntent>(extraBufferCapacity = 64)

    // Control changes -> LfoIntent (using new uri:symbol keys)
    private val controlIntents = merge(
        freqAId.map { LfoIntent.LfoA(it.asFloat()) },
        freqBId.map { LfoIntent.LfoB(it.asFloat()) },
        modeId.map {
            val modes = HyperLfoMode.entries
            val index = (it.asInt()).coerceIn(0, modes.size - 1)
            LfoIntent.Mode(modes[index])
        },
        linkId.map { LfoIntent.Link(it.asBoolean()) }
    )

    override val stateFlow: StateFlow<LfoUiState> =
        merge(controlIntents, uiIntents)
            .scan(LfoUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = LfoUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: LfoUiState, intent: LfoIntent): LfoUiState =
        when (intent) {
            is LfoIntent.LfoA -> state.copy(lfoA = intent.value / calculateMultiplier(state.lfoAMultiplier))
            is LfoIntent.LfoB -> state.copy(lfoB = intent.value / calculateMultiplier(state.lfoBMultiplier))
            is LfoIntent.LfoAMultiplier -> state.copy(lfoAMultiplier = intent.value)
            is LfoIntent.LfoBMultiplier -> state.copy(lfoBMultiplier = intent.value)
            is LfoIntent.Mode -> state.copy(mode = intent.mode)
            is LfoIntent.Link -> state.copy(linkEnabled = intent.enabled)
        }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setLfoA(value: Float) {
        freqAId.value = FloatValue(calculateFreq(value, stateFlow.value.lfoAMultiplier))
    }

    fun setLfoB(value: Float) {
        freqBId.value = FloatValue(calculateFreq(value, stateFlow.value.lfoBMultiplier))
    }

    fun setLfoAMultiplier(value: Float) {
        uiIntents.tryEmit(LfoIntent.LfoAMultiplier(value))
        // Re-calculate engine frequency with the new multiplier
        freqAId.value = FloatValue(calculateFreq(stateFlow.value.lfoA, value))
    }

    fun setLfoBMultiplier(value: Float) {
        uiIntents.tryEmit(LfoIntent.LfoBMultiplier(value))
        // Re-calculate engine frequency with the new multiplier
        freqBId.value = FloatValue(calculateFreq(stateFlow.value.lfoB, value))
    }

    private fun calculateMultiplier(multiplierState: Float): Float {
        // Slider: 0.0 (Fast) -> 100x multiplier
        // Slider: 1.0 (Slow) -> 1x multiplier
        return 1.0f + (1.0f - multiplierState) * 99.0f
    }

    private fun calculateFreq(base: Float, multiplierState: Float): Float {
        return base * calculateMultiplier(multiplierState)
    }

    companion object {
        fun previewFeature(state: LfoUiState = LfoUiState()): LfoFeature =
            object : LfoFeature {
                override val stateFlow: StateFlow<LfoUiState> = MutableStateFlow(state)
                override val actions: LfoPanelActions = LfoPanelActions.EMPTY
            }

        @Composable
        fun feature(): LfoFeature =
            synthViewModel<LfoViewModel, LfoFeature>()
    }
}
