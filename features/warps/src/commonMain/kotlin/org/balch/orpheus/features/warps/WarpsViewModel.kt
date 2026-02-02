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
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
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
    data class Algorithm(val value: Float, val fromSequencer: Boolean = false) : WarpsIntent
    data class Timbre(val value: Float, val fromSequencer: Boolean = false) : WarpsIntent
    data class CarrierLevel(val value: Float, val fromSequencer: Boolean = false) : WarpsIntent
    data class ModulatorLevel(val value: Float, val fromSequencer: Boolean = false) : WarpsIntent
    data class CarrierSource(val source: WarpsSource) : WarpsIntent
    data class ModulatorSource(val source: WarpsSource) : WarpsIntent
    data class Mix(val value: Float, val fromSequencer: Boolean = false) : WarpsIntent
    data class Restore(val state: WarpsUiState) : WarpsIntent
}

typealias WarpsFeature = SynthFeature<WarpsUiState, WarpsPanelActions>

/**
 * ViewModel for the Warps Meta-Modulator panel.
 *
 * Uses MVI pattern with flow { emit(initial); emitAll(updates) } for proper WhileSubscribed support.
 * Integrates with SynthController for MIDI, sequencer, and AI control.
 */
@Inject
@ViewModelKey(WarpsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class WarpsViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    presetLoader: org.balch.orpheus.core.presets.PresetLoader,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), WarpsFeature {

    override val actions = WarpsPanelActions(
        setAlgorithm = ::onAlgorithmChange,
        setTimbre = ::onTimbreChange,
        setCarrierLevel = ::onCarrierLevelChange,
        setModulatorLevel = ::onModulatorLevelChange,
        setCarrierSource = ::onCarrierSourceChange,
        setModulatorSource = ::onModulatorSourceChange,
        setMix = ::onMixChange
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<WarpsIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> WarpsIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        WarpsIntent.Restore(
            WarpsUiState(
                algorithm = preset.warpsAlgorithm,
                timbre = preset.warpsTimbre,
                carrierLevel = preset.warpsCarrierLevel,
                modulatorLevel = preset.warpsModulatorLevel,
                carrierSource = preset.warpsCarrierSource,
                modulatorSource = preset.warpsModulatorSource,
                mix = preset.warpsMix
            )
        )
    }

    // Control changes -> WarpsIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.WARPS_ALGORITHM -> WarpsIntent.Algorithm(event.value, fromSequencer)
            ControlIds.WARPS_TIMBRE -> WarpsIntent.Timbre(event.value, fromSequencer)
            ControlIds.WARPS_CARRIER_LEVEL -> WarpsIntent.CarrierLevel(event.value, fromSequencer)
            ControlIds.WARPS_MODULATOR_LEVEL -> WarpsIntent.ModulatorLevel(event.value, fromSequencer)
            ControlIds.WARPS_MIX -> WarpsIntent.Mix(event.value, fromSequencer)
            ControlIds.WARPS_CARRIER_SOURCE -> {
                val sources = WarpsSource.entries.toTypedArray()
                val index = (event.value * (sources.size - 1)).toInt().coerceIn(0, sources.size - 1)
                WarpsIntent.CarrierSource(sources[index])
            }
            ControlIds.WARPS_MODULATOR_SOURCE -> {
                val sources = WarpsSource.entries.toTypedArray()
                val index = (event.value * (sources.size - 1)).toInt().coerceIn(0, sources.size - 1)
                WarpsIntent.ModulatorSource(sources[index])
            }
            else -> null
        }
    }

    override val stateFlow: StateFlow<WarpsUiState> =
        merge(_userIntents, presetIntents, controlIntents)
        .scan(WarpsUiState()) { state, intent ->
            val newState = reduce(state, intent)
            applyToEngine(newState, intent)
            newState
        }
        .flowOn(dispatcherProvider.io)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
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
            is WarpsIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: WarpsUiState, intent: WarpsIntent) {
        when (intent) {
            // Skip engine calls for SEQUENCER events - engine is driven by audio-rate automation
            is WarpsIntent.Algorithm -> if (!intent.fromSequencer) engine.setWarpsAlgorithm(intent.value)
            is WarpsIntent.Timbre -> if (!intent.fromSequencer) engine.setWarpsTimbre(intent.value)
            is WarpsIntent.CarrierLevel -> if (!intent.fromSequencer) engine.setWarpsLevel1(intent.value)
            is WarpsIntent.ModulatorLevel -> if (!intent.fromSequencer) engine.setWarpsLevel2(intent.value)
            is WarpsIntent.CarrierSource -> engine.setWarpsCarrierSource(intent.source.ordinal)
            is WarpsIntent.ModulatorSource -> engine.setWarpsModulatorSource(intent.source.ordinal)
            is WarpsIntent.Mix -> if (!intent.fromSequencer) engine.setWarpsMix(intent.value)
            is WarpsIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: WarpsUiState) {
        engine.setWarpsAlgorithm(state.algorithm)
        engine.setWarpsTimbre(state.timbre)
        engine.setWarpsLevel1(state.carrierLevel)
        engine.setWarpsLevel2(state.modulatorLevel)
        engine.setWarpsCarrierSource(state.carrierSource.ordinal)
        engine.setWarpsModulatorSource(state.modulatorSource.ordinal)
        engine.setWarpsMix(state.mix)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun onAlgorithmChange(value: Float) {
        val fromSequencer = false // UI actions are not from sequencer
        _userIntents.tryEmit(WarpsIntent.Algorithm(value, fromSequencer))
        synthController.emitControlChange(ControlIds.WARPS_ALGORITHM, value, ControlEventOrigin.UI)
    }

    fun onTimbreChange(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(WarpsIntent.Timbre(value, fromSequencer))
        synthController.emitControlChange(ControlIds.WARPS_TIMBRE, value, ControlEventOrigin.UI)
    }

    fun onCarrierLevelChange(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(WarpsIntent.CarrierLevel(value, fromSequencer))
        synthController.emitControlChange(ControlIds.WARPS_CARRIER_LEVEL, value, ControlEventOrigin.UI)
    }

    fun onModulatorLevelChange(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(WarpsIntent.ModulatorLevel(value, fromSequencer))
        synthController.emitControlChange(ControlIds.WARPS_MODULATOR_LEVEL, value, ControlEventOrigin.UI)
    }

    fun onCarrierSourceChange(source: WarpsSource) {
        _userIntents.tryEmit(WarpsIntent.CarrierSource(source))
        val sources = WarpsSource.entries.toTypedArray()
        val value = if (sources.size > 1) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.WARPS_CARRIER_SOURCE, value, ControlEventOrigin.UI)
    }

    fun onModulatorSourceChange(source: WarpsSource) {
        _userIntents.tryEmit(WarpsIntent.ModulatorSource(source))
        val sources = WarpsSource.entries.toTypedArray()
        val value = if (sources.size > 1) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.WARPS_MODULATOR_SOURCE, value, ControlEventOrigin.UI)
    }

    fun onMixChange(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(WarpsIntent.Mix(value, fromSequencer))
        synthController.emitControlChange(ControlIds.WARPS_MIX, value, ControlEventOrigin.UI)
    }

    fun restoreState(state: WarpsUiState) {
        _userIntents.tryEmit(WarpsIntent.Restore(state))
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
