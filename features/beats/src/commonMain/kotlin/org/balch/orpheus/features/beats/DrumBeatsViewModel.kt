package org.balch.orpheus.features.beats

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
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

interface DrumBeatsFeature: SynthFeature<BeatsUiState, DrumBeatsPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.BEATS
            override val title = "Beats"

            override val markdown = """
                Algorithmic drum pattern generator with two modes: Drums (X/Y pattern morphing) and Euclidean (mathematically perfect beat distributions). Controls tempo, density, swing, randomness, and pattern mode.

                ## Modes
                - **Drums**: X/Y morphing across a 2D space of pre-built drum patterns. X selects the pattern family, Y morphs between variations.
                - **Euclidean**: Distributes hits evenly across a configurable step length for each voice. Creates polyrhythmic patterns.

                ## Controls
                - **X / Y**: Pattern morphing coordinates (Drums mode).
                - **BPM**: Tempo in beats per minute (60-200).
                - **DENSITY (x3)**: Hit density per voice — higher values produce more frequent triggers.
                - **EUCLIDEAN (x3)**: Step length per voice in Euclidean mode.
                - **RANDOMNESS**: Chance of random ghost notes or dropped hits.
                - **SWING**: Timing offset for off-beat notes, adding groove.
                - **MIX**: Output level of the beat generator.
                - **MODE**: Switch between Drums and Euclidean modes.

                ## Tips
                - Start in Drums mode and sweep X/Y for instant pattern exploration.
                - Switch to Euclidean for precise polyrhythmic layering.
                - Add SWING for a human feel; increase RANDOMNESS for generative variation.
            """.trimIndent()

            override val portControlKeys = mapOf(
                BeatsSymbol.X.controlId.key to "Pattern X coordinate (Drums mode)",
                BeatsSymbol.Y.controlId.key to "Pattern Y coordinate (Drums mode)",
                BeatsSymbol.BPM.controlId.key to "Tempo in beats per minute",
                DrumSymbol.MIX.controlId.key to "Drum synth output level",
                BeatsSymbol.RANDOMNESS.controlId.key to "Random ghost note / drop probability",
                BeatsSymbol.SWING.controlId.key to "Off-beat timing offset for groove",
                BeatsSymbol.MODE.controlId.key to "Pattern mode (0=Drums, 1=Euclidean)",
                BeatsSymbol.DENSITY_0.controlId.key to "Hit density for voice 0 (Bass Drum)",
                BeatsSymbol.DENSITY_1.controlId.key to "Hit density for voice 1 (Snare)",
                BeatsSymbol.DENSITY_2.controlId.key to "Hit density for voice 2 (Hi-Hat)",
                BeatsSymbol.EUCLIDEAN_0.controlId.key to "Euclidean step length for voice 0",
                BeatsSymbol.EUCLIDEAN_1.controlId.key to "Euclidean step length for voice 1",
                BeatsSymbol.EUCLIDEAN_2.controlId.key to "Euclidean step length for voice 2",
            )
        }
    }
}

/**
 * ViewModel for the Drum Beats panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for port-based engine interactions.
 * Keeps SynthEngine dependency for triggerDrum (pattern generator callback) and getCurrentTime (clock).
 * PatternGenerator state is updated as a side effect alongside controlFlow-driven state changes.
 */
@Inject
@ViewModelKey(DrumBeatsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*, *>>())
class DrumBeatsViewModel(
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val globalTempo: GlobalTempo,
) : ViewModel(), DrumBeatsFeature {

    // Control flows for beats ports
    private val xFlow = synthController.controlFlow(BeatsSymbol.X.controlId)
    private val yFlow = synthController.controlFlow(BeatsSymbol.Y.controlId)
    private val bpmFlow = synthController.controlFlow(BeatsSymbol.BPM.controlId)
    private val mixFlow = synthController.controlFlow(DrumSymbol.MIX.controlId)
    private val randomnessFlow = synthController.controlFlow(BeatsSymbol.RANDOMNESS.controlId)
    private val swingFlow = synthController.controlFlow(BeatsSymbol.SWING.controlId)
    private val modeFlow = synthController.controlFlow(BeatsSymbol.MODE.controlId)
    private val densityFlows = Array(3) { i -> synthController.controlFlow(BeatsSymbol.density(i).controlId) }
    private val euclideanFlows = Array(3) { i -> synthController.controlFlow(BeatsSymbol.euclidean(i).controlId) }

    private val patternGenerator = DrumBeatsGenerator { type, acc ->
        synthEngine.triggerDrum(type, acc)
    }
    private var clockJob: Job? = null

    // UI-only intents (Run, TickStep)
    private val uiIntents = MutableSharedFlow<DrumBeatsIntent>(extraBufferCapacity = 64)

    // Port-based control changes -> intents
    private val controlIntents = merge(
        xFlow.map { DrumBeatsIntent.SetX(it.asFloat()) },
        yFlow.map { DrumBeatsIntent.SetY(it.asFloat()) },
        bpmFlow.map { DrumBeatsIntent.SetBpm(it.asFloat()) },
        mixFlow.map { DrumBeatsIntent.SetMix(it.asFloat()) },
        randomnessFlow.map { DrumBeatsIntent.SetRandomness(it.asFloat()) },
        swingFlow.map { DrumBeatsIntent.SetSwing(it.asFloat()) },
        modeFlow.map {
            val mode = if (it.asInt() > 0) DrumBeatsGenerator.OutputMode.EUCLIDEAN
                else DrumBeatsGenerator.OutputMode.DRUMS
            DrumBeatsIntent.SetOutputMode(mode)
        },
        densityFlows[0].map { DrumBeatsIntent.SetDensity(0, it.asFloat()) },
        densityFlows[1].map { DrumBeatsIntent.SetDensity(1, it.asFloat()) },
        densityFlows[2].map { DrumBeatsIntent.SetDensity(2, it.asFloat()) },
        euclideanFlows[0].map { DrumBeatsIntent.SetEuclideanLength(0, it.asInt()) },
        euclideanFlows[1].map { DrumBeatsIntent.SetEuclideanLength(1, it.asInt()) },
        euclideanFlows[2].map { DrumBeatsIntent.SetEuclideanLength(2, it.asInt()) },
    )

    override val actions = DrumBeatsPanelActions(
        setX = xFlow.floatSetter(),
        setY = yFlow.floatSetter(),
        setDensity = ::setDensity,
        setRunning = ::setRunning,
        setBpm = ::setBpm,
        setOutputMode = ::setOutputMode,
        setEuclideanLength = ::setEuclideanLength,
        setRandomness = randomnessFlow.floatSetter(),
        setSwing = swingFlow.floatSetter(),
        setMix = mixFlow.floatSetter()
    )

    override val stateFlow: StateFlow<BeatsUiState> =
        merge(controlIntents, uiIntents)
            .scan(BeatsUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applySideEffects(intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = BeatsUiState()
            )

    init {
        // Sync GlobalTempo -> BPM port
        viewModelScope.launch(dispatcherProvider.io) {
            globalTempo.bpm.collect { bpm ->
                bpmFlow.value = FloatValue(bpm.toFloat())
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: BeatsUiState, intent: DrumBeatsIntent): BeatsUiState =
        when (intent) {
            is DrumBeatsIntent.SetX -> state.copy(x = intent.value)
            is DrumBeatsIntent.SetY -> state.copy(y = intent.value)
            is DrumBeatsIntent.SetDensity -> {
                val newD = state.densities.toMutableList()
                if (intent.index in newD.indices) newD[intent.index] = intent.value
                state.copy(densities = newD)
            }
            is DrumBeatsIntent.Run -> state.copy(isRunning = intent.running)
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
            is DrumBeatsIntent.TickStep -> state.copy(currentStep = intent.step)
        }

    // ═══════════════════════════════════════════════════════════
    // SIDE EFFECTS (pattern generator + clock only; engine handled by controlFlows)
    // ═══════════════════════════════════════════════════════════

    private fun applySideEffects(intent: DrumBeatsIntent) {
        when (intent) {
            is DrumBeatsIntent.SetX -> patternGenerator.setX(intent.value)
            is DrumBeatsIntent.SetY -> patternGenerator.setY(intent.value)
            is DrumBeatsIntent.SetDensity -> patternGenerator.setDensity(intent.index, intent.value)
            is DrumBeatsIntent.Run -> if (intent.running) startClock() else stopClock()
            is DrumBeatsIntent.SetOutputMode -> patternGenerator.outputMode = intent.mode
            is DrumBeatsIntent.SetEuclideanLength -> patternGenerator.setEuclideanLength(intent.index, intent.length)
            is DrumBeatsIntent.SetRandomness -> patternGenerator.setRandomness(intent.value)
            else -> { /* No pattern generator update needed for BPM, Swing, Mix, TickStep */ }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setDensity(index: Int, value: Float) {
        densityFlows[index].value = FloatValue(value)
    }

    fun setRunning(running: Boolean) {
        uiIntents.tryEmit(DrumBeatsIntent.Run(running))
    }

    fun setBpm(value: Float) {
        bpmFlow.value = FloatValue(value)
        globalTempo.setBpm(value.toDouble())
    }

    fun setOutputMode(mode: DrumBeatsGenerator.OutputMode) {
        modeFlow.value = IntValue(if (mode == DrumBeatsGenerator.OutputMode.EUCLIDEAN) 1 else 0)
    }

    fun setEuclideanLength(index: Int, length: Int) {
        euclideanFlows[index].value = IntValue(length)
    }

    // ═══════════════════════════════════════════════════════════
    // CLOCK LOGIC
    // ═══════════════════════════════════════════════════════════

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch(dispatcherProvider.io) {
            var nextTickTime = synthEngine.getCurrentTime()

            while (isActive) {
                val state = stateFlow.value
                val bpm = state.bpm
                val swing = state.swing

                // Seconds per tick at 24 PPQN: 60 / (BPM * 24) = 2.5 / BPM
                val baseSecondsPerTick = 2.5 / bpm

                val now = synthEngine.getCurrentTime()

                if (now >= nextTickTime) {
                    patternGenerator.tick()
                    val step = patternGenerator.getCurrentStep()
                    uiIntents.tryEmit(DrumBeatsIntent.TickStep(step))

                    val swingFactor = if (step % 2 == 0) (1.0 + swing * 0.5) else (1.0 - swing * 0.5)
                    val duration = baseSecondsPerTick * swingFactor

                    nextTickTime += duration

                    // Drift correction
                    if (now > nextTickTime + 0.1) {
                        nextTickTime = now + duration
                    }
                } else {
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
        uiIntents.tryEmit(DrumBeatsIntent.TickStep(0))
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
    data class SetX(val value: Float) : DrumBeatsIntent
    data class SetY(val value: Float) : DrumBeatsIntent
    data class SetDensity(val index: Int, val value: Float) : DrumBeatsIntent
    data class Run(val running: Boolean) : DrumBeatsIntent
    data class SetBpm(val value: Float) : DrumBeatsIntent
    data class SetOutputMode(val mode: DrumBeatsGenerator.OutputMode) : DrumBeatsIntent
    data class SetEuclideanLength(val index: Int, val length: Int) : DrumBeatsIntent
    data class SetRandomness(val value: Float) : DrumBeatsIntent
    data class SetSwing(val value: Float) : DrumBeatsIntent
    data class SetMix(val value: Float) : DrumBeatsIntent
    data class TickStep(val step: Int) : DrumBeatsIntent
}
