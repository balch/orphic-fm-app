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
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.presets.PresetLoader
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
    presetLoader: PresetLoader,
    synthController: SynthController,
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
    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            Ids.ALGORITHM -> WarpsIntent.Algorithm(event.value, fromSequencer)
            Ids.TIMBRE -> WarpsIntent.Timbre(event.value, fromSequencer)
            Ids.CARRIER_LEVEL -> WarpsIntent.CarrierLevel(event.value, fromSequencer)
            Ids.MODULATOR_LEVEL -> WarpsIntent.ModulatorLevel(event.value, fromSequencer)
            Ids.MIX -> WarpsIntent.Mix(event.value, fromSequencer)
            Ids.CARRIER_SOURCE -> {
                val sources = WarpsSource.entries.toTypedArray()
                val index = (event.value * (sources.size - 1)).toInt().coerceIn(0, sources.size - 1)
                WarpsIntent.CarrierSource(sources[index])
            }
            Ids.MODULATOR_SOURCE -> {
                val sources = WarpsSource.entries.toTypedArray()
                val index = (event.value * (sources.size - 1)).toInt().coerceIn(0, sources.size - 1)
                WarpsIntent.ModulatorSource(sources[index])
            }
            else -> null
        }
    }

    override val stateFlow: StateFlow<WarpsUiState> = flow {
        // Emit initial state from engine
        val initial = loadInitialState()
        applyFullState(initial)
        emit(initial)
        
        // Then emit from merged intent sources
        emitAll(
            merge(_userIntents, presetIntents, controlIntents)
                .onEach { intent -> if (intent != null) applyToEngine(intent) }
                .scan(initial) { state, intent -> if (intent != null) reduce(state, intent) else state }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WarpsUiState()
    )

    private fun loadInitialState(): WarpsUiState {
        return WarpsUiState(
            algorithm = engine.getWarpsAlgorithm(),
            timbre = engine.getWarpsTimbre(),
            carrierLevel = engine.getWarpsLevel1(),
            modulatorLevel = engine.getWarpsLevel2(),
            carrierSource = try { 
                WarpsSource.entries[engine.getWarpsCarrierSource()] 
            } catch (_: Exception) { 
                WarpsSource.SYNTH 
            },
            modulatorSource = try { 
                WarpsSource.entries[engine.getWarpsModulatorSource()] 
            } catch (_: Exception) { 
                WarpsSource.DRUMS 
            },
            mix = engine.getWarpsMix()
        )
    }

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

    private fun applyToEngine(intent: WarpsIntent) {
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
        _userIntents.tryEmit(WarpsIntent.Algorithm(value))
    }

    fun onTimbreChange(value: Float) {
        _userIntents.tryEmit(WarpsIntent.Timbre(value))
    }

    fun onCarrierLevelChange(value: Float) {
        _userIntents.tryEmit(WarpsIntent.CarrierLevel(value))
    }

    fun onModulatorLevelChange(value: Float) {
        _userIntents.tryEmit(WarpsIntent.ModulatorLevel(value))
    }

    fun onCarrierSourceChange(source: WarpsSource) {
        _userIntents.tryEmit(WarpsIntent.CarrierSource(source))
    }

    fun onModulatorSourceChange(source: WarpsSource) {
        _userIntents.tryEmit(WarpsIntent.ModulatorSource(source))
    }

    fun onMixChange(value: Float) {
        _userIntents.tryEmit(WarpsIntent.Mix(value))
    }

    fun restoreState(state: WarpsUiState) {
        _userIntents.tryEmit(WarpsIntent.Restore(state))
    }

    companion object Ids {
        // Control IDs for MIDI mapping
        const val ALGORITHM = "warps_algorithm"
        const val TIMBRE = "warps_timbre"
        const val CARRIER_LEVEL = "warps_carrier_level"
        const val MODULATOR_LEVEL = "warps_modulator_level"
        const val CARRIER_SOURCE = "warps_carrier_source"
        const val MODULATOR_SOURCE = "warps_modulator_source"
        const val MIX = "warps_mix"

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
