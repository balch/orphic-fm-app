package org.balch.orpheus.features.warps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ClassKey
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
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.FeatureCoroutineScope
import org.balch.orpheus.core.synthFeature

@Immutable
data class WarpsUiState(
    val algorithm: Float = 0.0f,
    val timbre: Float = 0.5f,
    val carrierLevel: Float = 0.5f,
    val modulatorLevel: Float = 0.5f,
    val carrierSource: WarpsSource = WarpsSource.SYNTH,
    val modulatorSource: WarpsSource = WarpsSource.DRUMS,
    val mix: Float = 0.0f
)

@Immutable
data class WarpsPanelActions(
    val setAlgorithm: (Float) -> Unit,
    val setTimbre: (Float) -> Unit,
    val setCarrierLevel: (Float) -> Unit,
    val setModulatorLevel: (Float) -> Unit,
    val setCarrierSource: (WarpsSource) -> Unit,
    val setModulatorSource: (WarpsSource) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object Companion {
        val EMPTY = WarpsPanelActions({}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Warps panel. */
private sealed interface WarpsIntent {
    data class Algorithm(val value: Float) : WarpsIntent
    data class Timbre(val value: Float) : WarpsIntent
    data class CarrierLevel(val value: Float) : WarpsIntent
    data class ModulatorLevel(val value: Float) : WarpsIntent
    data class CarrierSource(val source: WarpsSource) : WarpsIntent
    data class ModulatorSource(val source: WarpsSource) : WarpsIntent
    data class Mix(val value: Float) : WarpsIntent
}

interface WarpsFeature : SynthFeature<WarpsUiState, WarpsPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.WARPS
            override val title = "Matrix"

            override val markdown = """
        Cross-modulation matrix with 8 algorithms (Crossfade, Cross-folding, Ring Mod, XOR, Comparator, Vocoder, Chebyshev, Freq Shift). Controls carrier and modulator signals.

        ## Controls
        - **ALGORITHM**: Selects the modulation algorithm. Sweeps continuously through 8 modes.
        - **TIMBRE**: Controls the intensity or character of the selected algorithm.
        - **CARRIER LEVEL**: Input level for the carrier signal.
        - **MODULATOR LEVEL**: Input level for the modulator signal.
        - **MIX**: Dry/wet crossfade between the original and processed signal.

        ## Switches
        - **CARRIER SOURCE**: Selects the carrier input source.
        - **MODULATOR SOURCE**: Selects the modulator input source.

        ## Tips
        - Sweep ALGORITHM slowly to morph between modulation types.
        - Use TIMBRE to add subtle or extreme tonal variation within each algorithm.
            """.trimIndent()

            override val portControlKeys = mapOf(
                WarpsSymbol.ALGORITHM.controlId.key to "Modulation algorithm selection (continuous sweep through 8 modes)",
                WarpsSymbol.TIMBRE.controlId.key to "Intensity/character of the selected algorithm",
                WarpsSymbol.LEVEL1.controlId.key to "Carrier signal input level",
                WarpsSymbol.LEVEL2.controlId.key to "Modulator signal input level",
                WarpsSymbol.MIX.controlId.key to "Dry/wet crossfade",
                WarpsSymbol.CARRIER_SOURCE.controlId.key to "Carrier input source selection",
                WarpsSymbol.MODULATOR_SOURCE.controlId.key to "Modulator input source selection",
            )
        }
    }
}

/**
 * ViewModel for the Warps Meta-Modulator panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@Inject
@ClassKey(WarpsViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class WarpsViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : WarpsFeature {

    // Control flows for Warps plugin ports
    private val algorithmId = synthController.controlFlow(WarpsSymbol.ALGORITHM.controlId)
    private val timbreId = synthController.controlFlow(WarpsSymbol.TIMBRE.controlId)
    private val carrierLevelId = synthController.controlFlow(WarpsSymbol.LEVEL1.controlId)
    private val modulatorLevelId = synthController.controlFlow(WarpsSymbol.LEVEL2.controlId)
    private val carrierSourceId = synthController.controlFlow(WarpsSymbol.CARRIER_SOURCE.controlId)
    private val modulatorSourceId = synthController.controlFlow(WarpsSymbol.MODULATOR_SOURCE.controlId)
    private val mixId = synthController.controlFlow(WarpsSymbol.MIX.controlId)

    override val actions = WarpsPanelActions(
        setAlgorithm = algorithmId.floatSetter(),
        setTimbre = timbreId.floatSetter(),
        setCarrierLevel = carrierLevelId.floatSetter(),
        setModulatorLevel = modulatorLevelId.floatSetter(),
        setCarrierSource = carrierSourceId.enumSetter(),
        setModulatorSource = modulatorSourceId.enumSetter(),
        setMix = mixId.floatSetter()
    )

    // Control changes -> WarpsIntent
    private val controlIntents = merge(
        algorithmId.map { WarpsIntent.Algorithm(it.asFloat()) },
        timbreId.map { WarpsIntent.Timbre(it.asFloat()) },
        carrierLevelId.map { WarpsIntent.CarrierLevel(it.asFloat()) },
        modulatorLevelId.map { WarpsIntent.ModulatorLevel(it.asFloat()) },
        carrierSourceId.map {
            val sources = WarpsSource.entries
            val index = it.asInt().coerceIn(0, sources.size - 1)
            WarpsIntent.CarrierSource(sources[index])
        },
        modulatorSourceId.map {
            val sources = WarpsSource.entries
            val index = it.asInt().coerceIn(0, sources.size - 1)
            WarpsIntent.ModulatorSource(sources[index])
        },
        mixId.map { WarpsIntent.Mix(it.asFloat()) }
    )

    override val stateFlow: StateFlow<WarpsUiState> =
        controlIntents
            .scan(WarpsUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = WarpsUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: WarpsUiState, intent: WarpsIntent): WarpsUiState =
        when (intent) {
            is WarpsIntent.Algorithm -> state.copy(algorithm = intent.value)
            is WarpsIntent.Timbre -> state.copy(timbre = intent.value)
            is WarpsIntent.CarrierLevel -> state.copy(carrierLevel = intent.value)
            is WarpsIntent.ModulatorLevel -> state.copy(modulatorLevel = intent.value)
            is WarpsIntent.CarrierSource -> state.copy(carrierSource = intent.source)
            is WarpsIntent.ModulatorSource -> state.copy(modulatorSource = intent.source)
            is WarpsIntent.Mix -> state.copy(mix = intent.value)
        }

    companion object {

        fun previewFeature(state: WarpsUiState = WarpsUiState()): WarpsFeature =
            object : WarpsFeature {
                override val stateFlow: StateFlow<WarpsUiState> = MutableStateFlow(state)
                override val actions: WarpsPanelActions = WarpsPanelActions.EMPTY
            }

        @Composable
        fun feature(): WarpsFeature =
            synthFeature<WarpsViewModel, WarpsFeature>()
    }
}
