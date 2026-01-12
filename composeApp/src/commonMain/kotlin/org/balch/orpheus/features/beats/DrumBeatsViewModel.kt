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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.synth.DrumBeatsGenerator
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.presets.PresetLoader
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
    private val synthEngine: SynthEngine, // Captured as property
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), DrumBeatsFeature {

    private val patternGenerator = DrumBeatsGenerator(synthEngine)
    
    private val _uiState = MutableStateFlow(BeatsUiState())
    override val stateFlow: StateFlow<BeatsUiState> = _uiState.asStateFlow()
    
    private var clockJob: Job? = null
    
    override val actions = DrumBeatsPanelActions(
        setX = { v -> 
            _uiState.update { it.copy(x = v) }
            patternGenerator.setX(v)
            synthEngine.setBeatsX(v)
        },
        setY = { v -> 
            _uiState.update { it.copy(y = v) }
            patternGenerator.setY(v)
            synthEngine.setBeatsY(v)
        },
        setDensity = { i, v -> 
            _uiState.update { s -> 
                val newD = s.densities.toMutableList()
                newD[i] = v
                s.copy(densities = newD) 
            }
            patternGenerator.setDensity(i, v)
            synthEngine.setBeatsDensity(i, v)
        },
        setRunning = { running -> 
            _uiState.update { it.copy(isRunning = running) }
            if (running) {
                startClock()
            } else {
                stopClock()
            }
        },
        setBpm = { bpm ->
            val clamped = bpm.coerceIn(60f, 200f)
            _uiState.update { it.copy(bpm = clamped) }
            synthEngine.setBeatsBpm(clamped)
        },
        setOutputMode = { mode ->
            _uiState.update { it.copy(outputMode = mode) }
            patternGenerator.outputMode = mode
            synthEngine.setBeatsOutputMode(mode.ordinal)
        },
        setEuclideanLength = { i, len ->
            _uiState.update { s ->
                val newL = s.euclideanLengths.toMutableList()
                newL[i] = len
                s.copy(euclideanLengths = newL)
            }
            patternGenerator.setEuclideanLength(i, len)
            synthEngine.setBeatsEuclideanLength(i, len)
        },
        setRandomness = { v ->
            _uiState.update { it.copy(randomness = v) }
            patternGenerator.setRandomness(v)
            synthEngine.setBeatsRandomness(v)
        },
        setSwing = { v ->
            _uiState.update { it.copy(swing = v) }
            synthEngine.setBeatsSwing(v)
        },
        setMix = { v ->
            _uiState.update { it.copy(mix = v) }
            synthEngine.setBeatsMix(v)
        }
    )
    
    init {
        // Sync initial state to engine (so saving immediately works)
        val s = _uiState.value
        synthEngine.setBeatsX(s.x)
        synthEngine.setBeatsY(s.y)
        s.densities.forEachIndexed { i, v -> synthEngine.setBeatsDensity(i, v) }
        synthEngine.setBeatsBpm(s.bpm)
        synthEngine.setBeatsOutputMode(s.outputMode.ordinal)
        s.euclideanLengths.forEachIndexed { i, v -> synthEngine.setBeatsEuclideanLength(i, v) }
        synthEngine.setBeatsRandomness(s.randomness)
        synthEngine.setBeatsSwing(s.swing)
        synthEngine.setBeatsMix(s.mix)

        // Subscribe to presets
        viewModelScope.launch(dispatcherProvider.default) {
            presetLoader.presetFlow.collect { preset ->
                val outputMode = DrumBeatsGenerator.OutputMode.entries.getOrElse(preset.beatsOutputMode) { DrumBeatsGenerator.OutputMode.DRUMS }
                
                _uiState.update {
                    it.copy(
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
                }
                
                // Update Pattern Generator
                patternGenerator.setX(preset.beatsX)
                patternGenerator.setY(preset.beatsY)
                preset.beatsDensities.forEachIndexed { i, d -> patternGenerator.setDensity(i, d) }
                patternGenerator.outputMode = outputMode
                preset.beatsEuclideanLengths.forEachIndexed { i, l -> patternGenerator.setEuclideanLength(i, l) }
                patternGenerator.setRandomness(preset.beatsRandomness)

                // Sync to Engine
                synthEngine.setBeatsX(preset.beatsX)
                synthEngine.setBeatsY(preset.beatsY)
                preset.beatsDensities.forEachIndexed { i, d -> synthEngine.setBeatsDensity(i, d) }
                synthEngine.setBeatsBpm(preset.beatsBpm)
                synthEngine.setBeatsOutputMode(preset.beatsOutputMode)
                preset.beatsEuclideanLengths.forEachIndexed { i, l -> synthEngine.setBeatsEuclideanLength(i, l) }
                synthEngine.setBeatsRandomness(preset.beatsRandomness)
                synthEngine.setBeatsSwing(preset.beatsSwing)
                // Use preset value, which defaults to 0.7f in DronePreset now
                synthEngine.setBeatsMix(preset.beatsMix)
            }
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch(dispatcherProvider.io) {
            // 24 PPQN at current BPM
            // At 120 BPM: 1 beat = 500ms, 1 tick = 500ms/24 ≈ 20.8ms
            while (isActive) {
                val state = _uiState.value
                val bpm = state.bpm
                val swing = state.swing
                var tickDurationMs = (60_000.0 / bpm / 24.0).toLong()
                
                // Swing Logic: Adjust duration of ticks based on current Step parity
                // Step 0 (Even) -> Longer; Step 1 (Odd) -> Shorter
                // Ticks 0..5 belong to Step 0? 
                // Wait, patternGenerator.tick() updates internal state.
                // We should check step *before* tick? Or after?
                // Tick processing advances step every 6 ticks.
                // Step 0 is ticks 0,1,2,3,4,5.
                // If swing is active, ticks 0..5 should correspond to the "long" note.
                // Ticks 6..11 should correspond to the "short" note.
                // TopographicPatternGenerator.step increments every 6 ticks.
                // So if step % 2 == 0, we are in "long" phase.
                
                val currentStep = patternGenerator.getCurrentStep()
                val swingFactor = if (currentStep % 2 == 0) (1.0 + swing * 0.5) else (1.0 - swing * 0.5)
                tickDurationMs = (tickDurationMs * swingFactor).toLong()

                patternGenerator.tick()
                
                // Update step display (every 6 ticks = 1 step)
                val step = patternGenerator.getCurrentStep()
                _uiState.update { it.copy(currentStep = step) }
                
                delay(tickDurationMs)
            }
        }
    }
    
    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        patternGenerator.reset()
        _uiState.update { it.copy(currentStep = 0) }
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
