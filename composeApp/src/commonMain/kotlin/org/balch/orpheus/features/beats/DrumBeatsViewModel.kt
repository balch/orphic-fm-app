package org.balch.orpheus.features.beats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.synth.DrumBeatsGenerator
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel

@Immutable
data class BeatsUiState(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val densities: List<Float> = listOf(0.5f, 0.5f, 0.5f),
    val isRunning: Boolean = false,
    val bpm: Float = 120f,
    val currentStep: Int = 0,
    val outputMode: DrumBeatsGenerator.OutputMode = DrumBeatsGenerator.OutputMode.DRUMS,
    val euclideanLengths: List<Int> = listOf(16, 16, 16),
    val randomness: Float = 0f,
    val swing: Float = 0f,
    val mix: Float = 0.7f
)

data class DrumBeatsPanelActions(
    val setX: (Float) -> Unit,
    val setY: (Float) -> Unit,
    val setDensity: (Int, Float) -> Unit,
    val setRunning: (Boolean) -> Unit,
    val setBpm: (Float) -> Unit,
    val setOutputMode: (DrumBeatsGenerator.OutputMode) -> Unit,
    val setEuclideanLength: (Int, Int) -> Unit,
    val setRandomness: (Float) -> Unit,
    val setSwing: (Float) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object Companion {
        val EMPTY = DrumBeatsPanelActions({}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {})
    }
}

typealias DrumBeatsFeature = SynthFeature<BeatsUiState, DrumBeatsPanelActions>


@ViewModelKey(DrumBeatsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DrumBeatsViewModel @Inject constructor(
    private val synthEngine: SynthEngine,
    presetLoader: PresetLoader,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), DrumBeatsFeature {

    private val patternGenerator = DrumBeatsGenerator(synthEngine)
    private var clockJob: Job? = null

    private val _userIntents = MutableSharedFlow<DrumBeatsIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val presetIntents = presetLoader.presetFlow.map { preset ->
        val outputMode = DrumBeatsGenerator.OutputMode.entries.getOrElse(preset.beatsOutputMode) { DrumBeatsGenerator.OutputMode.DRUMS }
        DrumBeatsIntent.Restore(
            BeatsUiState(
                x = preset.beatsX,
                y = preset.beatsY,
                densities = preset.beatsDensities,
                bpm = preset.beatsBpm,
                outputMode = outputMode,
                euclideanLengths = preset.beatsEuclideanLengths,
                randomness = preset.beatsRandomness,
                swing = preset.beatsSwing,
                mix = preset.beatsMix
            )
        )
    }

    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.BEATS_RUN -> DrumBeatsIntent.Run(event.value > 0.5f, fromSequencer)
            ControlIds.BEATS_X -> DrumBeatsIntent.SetX(event.value, fromSequencer)
            ControlIds.BEATS_Y -> DrumBeatsIntent.SetY(event.value, fromSequencer)
            ControlIds.BEATS_BPM -> DrumBeatsIntent.SetBpm(event.value, fromSequencer)
            ControlIds.BEATS_MIX -> DrumBeatsIntent.SetMix(event.value, fromSequencer)
            ControlIds.BEATS_RANDOMNESS -> DrumBeatsIntent.SetRandomness(event.value, fromSequencer)
            ControlIds.BEATS_SWING -> DrumBeatsIntent.SetSwing(event.value, fromSequencer)
            ControlIds.BEATS_MODE -> {
                val mode = if (event.value > 0.5f) DrumBeatsGenerator.OutputMode.EUCLIDEAN else DrumBeatsGenerator.OutputMode.DRUMS
                DrumBeatsIntent.SetOutputMode(mode, fromSequencer)
            }
            else -> {
                // Handling indexed properties (Density, Euclid Length)
                val d1 = if (event.controlId == ControlIds.beatsDensity(0)) DrumBeatsIntent.SetDensity(0, event.value, fromSequencer) else null
                val d2 = if (event.controlId == ControlIds.beatsDensity(1)) DrumBeatsIntent.SetDensity(1, event.value, fromSequencer) else null
                val d3 = if (event.controlId == ControlIds.beatsDensity(2)) DrumBeatsIntent.SetDensity(2, event.value, fromSequencer) else null
                
                // Check lengths (value is 0..1, map to 1..32)
                val lValue = (event.value * 31 + 1).toInt().coerceIn(1, 32)
                val l1 = if (event.controlId == ControlIds.beatsEuclideanLength(0)) DrumBeatsIntent.SetEuclideanLength(0, lValue, fromSequencer) else null
                val l2 = if (event.controlId == ControlIds.beatsEuclideanLength(1)) DrumBeatsIntent.SetEuclideanLength(1, lValue, fromSequencer) else null
                val l3 = if (event.controlId == ControlIds.beatsEuclideanLength(2)) DrumBeatsIntent.SetEuclideanLength(2, lValue, fromSequencer) else null
                
                d1 ?: d2 ?: d3 ?: l1 ?: l2 ?: l3
            }
        }
    }

    override val stateFlow: StateFlow<BeatsUiState> = flow {
        // Initial state logic (could load from generic persistence or default)
        val initial = BeatsUiState()
        applyFullState(initial)
        emit(initial)
        
        emitAll(
            merge(_userIntents, presetIntents, controlIntents)
                .filterNotNull() // Filter out nulls from controlIntents
                .onEach { intent -> applyToEngine(intent) }
                .scan(initial) { state, intent -> reduce(state, intent) }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BeatsUiState()
    )

    private fun reduce(state: BeatsUiState, intent: DrumBeatsIntent): BeatsUiState {
        return when (intent) {
            is DrumBeatsIntent.SetX -> state.copy(x = intent.value)
            is DrumBeatsIntent.SetY -> state.copy(y = intent.value)
            is DrumBeatsIntent.SetDensity -> {
                val newD = state.densities.toMutableList()
                if (intent.index in newD.indices) newD[intent.index] = intent.value
                state.copy(densities = newD)
            }
            is DrumBeatsIntent.Run -> {
                if (state.isRunning != intent.running) {
                    if (intent.running) startClock() else stopClock()
                }
                state.copy(isRunning = intent.running)
            }
            is DrumBeatsIntent.SetBpm -> state.copy(bpm = intent.value.coerceIn(60f, 200f))
            is DrumBeatsIntent.SetOutputMode -> state.copy(outputMode = intent.mode)
            is DrumBeatsIntent.SetEuclideanLength -> {
                val newL = state.euclideanLengths.toMutableList()
                if (intent.index in newL.indices) newL[intent.index] = intent.length
                state.copy(euclideanLengths = newL)
            }
            is DrumBeatsIntent.SetRandomness -> state.copy(randomness = intent.value)
            is DrumBeatsIntent.SetSwing -> state.copy(swing = intent.value)
            is DrumBeatsIntent.SetMix -> state.copy(mix = intent.value)
            is DrumBeatsIntent.Restore -> {
                // When restoring, we might need to sync clock state if running state changes?
                // For simplicity, if new state is run=true and we are not running, start.
                if (intent.state.isRunning && !state.isRunning) startClock()
                else if (!intent.state.isRunning && state.isRunning) stopClock()
                intent.state
            }
            is DrumBeatsIntent.TickStep -> state.copy(currentStep = intent.step)
        }
    }

    private fun applyToEngine(intent: DrumBeatsIntent) {
        when (intent) {
            is DrumBeatsIntent.SetX -> {
                patternGenerator.setX(intent.value)
                if (!intent.fromSequencer) synthEngine.setBeatsX(intent.value)
            }
            is DrumBeatsIntent.SetY -> {
                patternGenerator.setY(intent.value)
                if (!intent.fromSequencer) synthEngine.setBeatsY(intent.value)
            }
            is DrumBeatsIntent.SetDensity -> {
                patternGenerator.setDensity(intent.index, intent.value)
                if (!intent.fromSequencer) synthEngine.setBeatsDensity(intent.index, intent.value)
            }
            is DrumBeatsIntent.Run -> {
                // Engine doesn't track running state efficiently, it expects ticks.
                // However, we can use this to perhaps reset or prepare.
            }
            is DrumBeatsIntent.SetBpm -> if (!intent.fromSequencer) synthEngine.setBeatsBpm(intent.value)
            is DrumBeatsIntent.SetOutputMode -> {
                patternGenerator.outputMode = intent.mode
                if (!intent.fromSequencer) synthEngine.setBeatsOutputMode(intent.mode.ordinal)
            }
            is DrumBeatsIntent.SetEuclideanLength -> {
                patternGenerator.setEuclideanLength(intent.index, intent.length)
                if (!intent.fromSequencer) synthEngine.setBeatsEuclideanLength(intent.index, intent.length)
            }
            is DrumBeatsIntent.SetRandomness -> {
                patternGenerator.setRandomness(intent.value)
                if (!intent.fromSequencer) synthEngine.setBeatsRandomness(intent.value)
            }
            is DrumBeatsIntent.SetSwing -> if (!intent.fromSequencer) synthEngine.setBeatsSwing(intent.value)
            is DrumBeatsIntent.SetMix -> if (!intent.fromSequencer) synthEngine.setBeatsMix(intent.value)
            is DrumBeatsIntent.Restore -> applyFullState(intent.state)
            is DrumBeatsIntent.TickStep -> { 
                // UI only update, no engine push needed 
            }
        }
    }

    private fun applyFullState(state: BeatsUiState) {
        patternGenerator.setX(state.x)
        patternGenerator.setY(state.y)
        state.densities.forEachIndexed { i, d -> 
            patternGenerator.setDensity(i, d)
            synthEngine.setBeatsDensity(i, d)
        }
        synthEngine.setBeatsBpm(state.bpm)
        patternGenerator.outputMode = state.outputMode
        synthEngine.setBeatsOutputMode(state.outputMode.ordinal)
        state.euclideanLengths.forEachIndexed { i, l -> 
            patternGenerator.setEuclideanLength(i, l)
            synthEngine.setBeatsEuclideanLength(i, l)
        }
        patternGenerator.setRandomness(state.randomness)
        synthEngine.setBeatsRandomness(state.randomness)
        synthEngine.setBeatsSwing(state.swing)
        synthEngine.setBeatsMix(state.mix)
        
        if (state.isRunning) startClock() else stopClock()
    }

    override val actions = DrumBeatsPanelActions(
        setX = { _userIntents.tryEmit(DrumBeatsIntent.SetX(it)) },
        setY = { _userIntents.tryEmit(DrumBeatsIntent.SetY(it)) },
        setDensity = { i, v -> _userIntents.tryEmit(DrumBeatsIntent.SetDensity(i, v)) },
        setRunning = { _userIntents.tryEmit(DrumBeatsIntent.Run(it)) },
        setBpm = { _userIntents.tryEmit(DrumBeatsIntent.SetBpm(it)) },
        setOutputMode = { _userIntents.tryEmit(DrumBeatsIntent.SetOutputMode(it)) },
        setEuclideanLength = { i, l -> _userIntents.tryEmit(DrumBeatsIntent.SetEuclideanLength(i, l)) },
        setRandomness = { _userIntents.tryEmit(DrumBeatsIntent.SetRandomness(it)) },
        setSwing = { _userIntents.tryEmit(DrumBeatsIntent.SetSwing(it)) },
        setMix = { _userIntents.tryEmit(DrumBeatsIntent.SetMix(it)) }
    )

    // Clock Logic (emits TickStep intents)
    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch(dispatcherProvider.io) {
            while (isActive) {
                // Access current state via patternGenerator since it's the source of truth for step logic
                // But we need BPM/Swing which are in state. 
                // Ideally reading latest state from flow, but here we can rely on patternGenerator having latest params
                // EXCEPT BPM/Swing which are not stored in generator explicitly for timing calculation? 
                
                // We'll read the stateFlow value. Note: this might be slightly racy if flow hasn't emitted yet.
                val state = stateFlow.value 
                val bpm = state.bpm
                val swing = state.swing
                
                var tickDurationMs = (60_000.0 / bpm / 24.0).toLong()
                val currentStep = patternGenerator.getCurrentStep()
                // Simple swing logic (same as before)
                val swingFactor = if (currentStep % 2 == 0) (1.0 + swing * 0.5) else (1.0 - swing * 0.5)
                tickDurationMs = (tickDurationMs * swingFactor).toLong()

                patternGenerator.tick()
                val step = patternGenerator.getCurrentStep()
                
                _userIntents.tryEmit(DrumBeatsIntent.TickStep(step)) // Internal intent for UI update
                
                delay(tickDurationMs)
            }
        }
    }
    
    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        patternGenerator.reset()
        _userIntents.tryEmit(DrumBeatsIntent.TickStep(0))
    }
    
    override fun onCleared() {
        super.onCleared()
        stopClock()
    }

    companion object Companion {
        fun previewFeature(state: BeatsUiState = BeatsUiState()): DrumBeatsFeature =
            object : DrumBeatsFeature {
                override val stateFlow: StateFlow<BeatsUiState> = MutableStateFlow(state)
                override val actions: DrumBeatsPanelActions = DrumBeatsPanelActions.EMPTY
            }

        @Composable
        fun feature(): DrumBeatsFeature =
            synthViewModel<DrumBeatsViewModel, DrumBeatsFeature>()
    }
}

private sealed interface DrumBeatsIntent {
    data class SetX(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetY(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetDensity(val index: Int, val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class Run(val running: Boolean, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetBpm(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetOutputMode(val mode: DrumBeatsGenerator.OutputMode, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetEuclideanLength(val index: Int, val length: Int, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetRandomness(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetSwing(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class SetMix(val value: Float, val fromSequencer: Boolean = false) : DrumBeatsIntent
    data class Restore(val state: BeatsUiState) : DrumBeatsIntent
    data class TickStep(val step: Int) : DrumBeatsIntent
}
