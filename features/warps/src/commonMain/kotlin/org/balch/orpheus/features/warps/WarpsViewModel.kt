package org.balch.orpheus.features.warps

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol
import org.balch.orpheus.core.synthViewModel

@Immutable
data class WarpsUiState(
    val algorithm: Float = 0.0f,
    val timbre: Float = 0.5f,
    val carrierLevel: Float = 0.5f,
    val modulatorLevel: Float = 0.5f,
    val carrierSource: WarpsSource = WarpsSource.SYNTH,
    val modulatorSource: WarpsSource = WarpsSource.DRUMS,
    val mix: Float = 0.5f
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

typealias WarpsFeature = SynthFeature<WarpsUiState, WarpsPanelActions>

/**
 * ViewModel for the Warps Meta-Modulator panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@ViewModelKey(WarpsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class WarpsViewModel @Inject constructor(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), WarpsFeature {

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
                scope = viewModelScope,
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
            synthViewModel<WarpsViewModel, WarpsFeature>()
    }
}
