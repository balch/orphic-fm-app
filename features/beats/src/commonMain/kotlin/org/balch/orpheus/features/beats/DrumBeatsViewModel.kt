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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.beatsDensities
import org.balch.orpheus.core.presets.beatsEuclideanLengths
import org.balch.orpheus.core.presets.beatsMix
import org.balch.orpheus.core.presets.beatsOutputMode
import org.balch.orpheus.core.presets.beatsRandomness
import org.balch.orpheus.core.presets.beatsSwing
import org.balch.orpheus.core.presets.beatsX
import org.balch.orpheus.core.presets.beatsY
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.plugins.drum.engine.DrumBeatsGenerator

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

@Immutable
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
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val globalTempo: GlobalTempo,
    private val presetLoader: org.balch.orpheus.core.presets.PresetLoader
) : ViewModel(), DrumBeatsFeature {

    private val patternGenerator = DrumBeatsGenerator { type, acc -> 
        synthEngine.triggerDrum(type, acc)
    }
    private var clockJob: Job? = null

    private val _userIntents = MutableSharedFlow<DrumBeatsIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )



    private val controlIntents = synthController.onControlChange.map { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.BEATS_RUN -> DrumBeatsIntent.Run(event.value > 0.5f, fromSequencer)
            ControlIds.BEATS_X -> DrumBeatsIntent.SetX(event.value, fromSequencer)
            ControlIds.BEATS_Y -> DrumBeatsIntent.SetY(event.value, fromSequencer)
            ControlIds.BPM -> DrumBeatsIntent.SetBpm(event.value, fromSequencer)
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
        // Initial state logic from preset
        val preset = presetLoader.presetFlow.first()
        val initial = BeatsUiState(
            x = preset.beatsX,
            y = preset.beatsY,
            densities = preset.beatsDensities.padEnd(3, 0.5f),
            bpm = preset.bpm.toFloat(),
            outputMode = DrumBeatsGenerator.OutputMode.entries.getOrElse(preset.beatsOutputMode) { DrumBeatsGenerator.OutputMode.DRUMS },
            euclideanLengths = preset.beatsEuclideanLengths.padEnd(3, 16),
            randomness = preset.beatsRandomness,
            swing = preset.beatsSwing,
            mix = preset.beatsMix
        )

        emit(initial)
        
        // Subscribe to GlobalTempo updates
        val tempoIntents = globalTempo.bpm.map { bpm ->
            DrumBeatsIntent.SetBpm(bpm.toFloat(), fromSequencer = true) // Treat as sequencer update to avoid loop
        }
        
        emitAll(
            merge(_userIntents, controlIntents, tempoIntents)
                .filterNotNull() // Filter out nulls from controlIntents
                .scan(initial) { state, intent ->
                    val newState = reduce(state, intent)
                    applyToEngine(newState, intent)
                    newState
                }
        )
    }
    .flowOn(dispatcherProvider.io)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
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
                    // Clock start/stop handled by side effect
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
                // Side effects handled by applyToEngine
                intent.state
            }
            is DrumBeatsIntent.TickStep -> state.copy(currentStep = intent.step)
        }
    }

    private fun applyToEngine(state: BeatsUiState, intent: DrumBeatsIntent) {
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
                if (intent.running) startClock(state) else stopClock()
            }
            is DrumBeatsIntent.SetBpm -> {
                // Update GlobalTempo so REPL and Flux sync
                if (!intent.fromSequencer) {
                    globalTempo.setBpm(intent.value.toDouble())
                    synthEngine.setBeatsBpm(intent.value)
                }
            }
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
        // Also update GlobalTempo for cross-module sync
        globalTempo.setBpm(state.bpm.toDouble())
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
        
        if (state.isRunning) startClock(state) else stopClock()
    }

    override val actions = DrumBeatsPanelActions(
        setX = ::setX,
        setY = ::setY,
        setDensity = ::setDensity,
        setRunning = ::setRunning,
        setBpm = ::setBpm,
        setOutputMode = ::setOutputMode,
        setEuclideanLength = ::setEuclideanLength,
        setRandomness = ::setRandomness,
        setSwing = ::setSwing,
        setMix = ::setMix
    )

    fun setX(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetX(value))
        synthController.emitControlChange(ControlIds.BEATS_X, value, ControlEventOrigin.UI)
    }

    fun setY(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetY(value))
        synthController.emitControlChange(ControlIds.BEATS_Y, value, ControlEventOrigin.UI)
    }

    fun setDensity(index: Int, value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetDensity(index, value))
        synthController.emitControlChange(ControlIds.beatsDensity(index), value, ControlEventOrigin.UI)
    }

    fun setRunning(running: Boolean) {
        _userIntents.tryEmit(DrumBeatsIntent.Run(running))
        synthController.emitControlChange(ControlIds.BEATS_RUN, if (running) 1f else 0f, ControlEventOrigin.UI)
    }

    fun setBpm(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetBpm(value))
        synthController.emitControlChange(ControlIds.BPM, value, ControlEventOrigin.UI)
    }

    fun setOutputMode(mode: DrumBeatsGenerator.OutputMode) {
        _userIntents.tryEmit(DrumBeatsIntent.SetOutputMode(mode))
        val v = if (mode == DrumBeatsGenerator.OutputMode.EUCLIDEAN) 1f else 0f
        synthController.emitControlChange(ControlIds.BEATS_MODE, v, ControlEventOrigin.UI)
    }

    fun setEuclideanLength(index: Int, length: Int) {
        _userIntents.tryEmit(DrumBeatsIntent.SetEuclideanLength(index, length))
        val v = (length - 1) / 31f
        synthController.emitControlChange(ControlIds.beatsEuclideanLength(index), v, ControlEventOrigin.UI)
    }

    fun setRandomness(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetRandomness(value))
        synthController.emitControlChange(ControlIds.BEATS_RANDOMNESS, value, ControlEventOrigin.UI)
    }

    fun setSwing(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetSwing(value))
        synthController.emitControlChange(ControlIds.BEATS_SWING, value, ControlEventOrigin.UI)
    }

    fun setMix(value: Float) {
        _userIntents.tryEmit(DrumBeatsIntent.SetMix(value))
        synthController.emitControlChange(ControlIds.BEATS_MIX, value, ControlEventOrigin.UI)
    }

    // Clock Logic (emits TickStep intents)
    private fun startClock(initialState: BeatsUiState) {
        clockJob?.cancel()
        clockJob = viewModelScope.launch(dispatcherProvider.io) {
            // Align start time to current audio time
            var nextTickTime = synthEngine.getCurrentTime()
            
            while (isActive) {
                // We need the LATEST state properties. 
                // Since stateFlow might be uninitialized, we use a local BPM tracked from intents or the stream.
                // But let's assume if startClock is called via scan, stateFlow might be ready SOON.
                // Better yet, let's just use the state passed in and eventually move to a collector.
                val state = stateFlow.value
                val bpm = state.bpm
                val swing = state.swing
                
                // Calculate tick duration based on 24 PPQN standard
                // Seconds per tick = 60 / (BPM * 24) = 2.5 / BPM
                val baseSecondsPerTick = 2.5 / bpm
                
                val now = synthEngine.getCurrentTime()
                
                if (now >= nextTickTime) {
                    // Execute tick
                    patternGenerator.tick()
                    val step = patternGenerator.getCurrentStep()
                    _userIntents.tryEmit(DrumBeatsIntent.TickStep(step))
                    
                    // Calculate next tick time with swing applied
                    // Swing affects even/odd 16th notes (every 6 ticks at 24 PPQN)
                    // We apply swing micro-timing to every tick for simplicity or specific grid steps?
                    // DrumBeatsGenerator has 'resolution = 6'.
                    // The standard swing usually delays the even 16th notes.
                    // Since we tick at resolution (PPQN), we should apply swing to the *duration* of the specific step we are in.
                    
                    // Simple swing:
                    // If we assume we are just stepping forward, we can apply swing factor to the interval.
                    // But DrumBeatsGenerator.tick() handles the sub-steps.
                    // Let's stick to the previous simple logic but in seconds.
                    val swingFactor = if (step % 2 == 0) (1.0 + swing * 0.5) else (1.0 - swing * 0.5)
                    val duration = baseSecondsPerTick * swingFactor
                    
                    nextTickTime += duration
                    
                    // Drift correction: if we fell too far behind (e.g. debugging pause), reset
                    if (now > nextTickTime + 0.1) {
                        nextTickTime = now + duration
                    }
                } else {
                    // Wait until next tick
                    val waitMs = ((nextTickTime - now) * 1000).toLong().coerceAtLeast(1)
                    delay(waitMs)
                }
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

// Extension function for List padding
private fun <T> List<T>.padEnd(size: Int, element: T): List<T> {
    return if (this.size >= size) this.take(size) else this + List(size - this.size) { element }
}
