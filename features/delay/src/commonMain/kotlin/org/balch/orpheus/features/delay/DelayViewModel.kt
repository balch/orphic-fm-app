package org.balch.orpheus.features.delay

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

import kotlinx.coroutines.flow.SharingStarted
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.FeatureCoroutineScope
import org.balch.orpheus.core.synthFeature

@Immutable
data class DelayUiState(
    val time1: Float = 0.3f,
    val time2: Float = 0.3f,
    val mod1: Float = 0.0f,
    val mod2: Float = 0.0f,
    val feedback: Float = 0.5f,
    val mix: Float = 0.0f,
    val isLfoSource: Boolean = true,
    val isTriangleWave: Boolean = true
)

@Immutable
data class DelayPanelActions(
    val setTime1: (Float) -> Unit,
    val setMod1: (Float) -> Unit,
    val setTime2: (Float) -> Unit,
    val setMod2: (Float) -> Unit,
    val setFeedback: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setSource: (Boolean) -> Unit,
    val setWaveform: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DelayPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Delay panel. */
private sealed interface DelayIntent {
    data class Time1(val value: Float) : DelayIntent
    data class Time2(val value: Float) : DelayIntent
    data class Mod1(val value: Float) : DelayIntent
    data class Mod2(val value: Float) : DelayIntent
    data class Feedback(val value: Float) : DelayIntent
    data class Mix(val value: Float) : DelayIntent
    data class Source(val isLfo: Boolean) : DelayIntent
    data class Waveform(val isTriangle: Boolean) : DelayIntent
}

interface DelayFeature : SynthFeature<DelayUiState, DelayPanelActions> {

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {

        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.DELAY
            override val title = "Delay"

            override val markdown = """
        Stereo dual-tap delay with modulation. Creates echo, rhythmic repeats, and shimmering textures.

        ## Controls
        - **TIME A / TIME B**: Set the delay time for each tap independently. Short times create chorus/flanger effects; longer times create distinct echoes.
        - **DELAY A / DELAY B** (MOD 1/2): Modulation depth for each delay line. Adds pitch wobble and movement to the echoes.
        - **FEEDBACK** (shown as the infinity symbol): Controls how many times the echo repeats. Low values give a single slapback; high values create long trails. Be careful above 0.9 — it can self-oscillate.
        - **MIX**: Dry/wet blend. 0 = no delay, 1 = fully wet.
        - **MOD SOURCE** toggle (LFO/SELF): Choose whether the modulation comes from the global LFO or from the delay's own feedback path.
        - **WAVEFORM** toggle (TRI/SQR): Shape of the modulation oscillator. Triangle is smooth; square creates a more dramatic pitch-shift effect.

        ## Tips
        - Set TIME A and TIME B to slightly different values for a wide stereo spread.
        - Short delay times (< 0.1) with moderate feedback create metallic resonances.
        - Use LFO modulation with triangle wave for tape-delay-style warble.
        - Pair with the Resonator for reverberant delay tails.
    """.trimIndent()

            override val portControlKeys = mapOf(
                DelaySymbol.TIME_1.controlId.key to "Delay time for tap A",
                DelaySymbol.TIME_2.controlId.key to "Delay time for tap B",
                DelaySymbol.MOD_DEPTH_1.controlId.key to "Modulation depth for delay line A",
                DelaySymbol.MOD_DEPTH_2.controlId.key to "Modulation depth for delay line B",
                DelaySymbol.FEEDBACK.controlId.key to "Echo repeats / feedback amount",
                DelaySymbol.MIX.controlId.key to "Dry/wet blend for the delay effect",
                DelaySymbol.MOD_SOURCE.controlId.key to "Modulation source (0=self-feedback, 1=LFO)",
                DelaySymbol.LFO_WAVEFORM.controlId.key to "LFO waveform shape (0=square, 1=triangle)",
            )
        }
    }
}

/**
 * ViewModel for the Mod Delay panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@Inject
@ClassKey(DelayViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class DelayViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : DelayFeature {

    // Control flows for Delay plugin ports
    private val time1Id = synthController.controlFlow(DelaySymbol.TIME_1.controlId)
    private val time2Id = synthController.controlFlow(DelaySymbol.TIME_2.controlId)
    private val mod1Id = synthController.controlFlow(DelaySymbol.MOD_DEPTH_1.controlId)
    private val mod2Id = synthController.controlFlow(DelaySymbol.MOD_DEPTH_2.controlId)
    private val feedbackId = synthController.controlFlow(DelaySymbol.FEEDBACK.controlId)
    private val mixId = synthController.controlFlow(DelaySymbol.MIX.controlId)
    private val modSourceId = synthController.controlFlow(DelaySymbol.MOD_SOURCE.controlId)
    private val waveformId = synthController.controlFlow(DelaySymbol.LFO_WAVEFORM.controlId)

    override val actions = DelayPanelActions(
        setTime1 = time1Id.floatSetter(),
        setMod1 = mod1Id.floatSetter(),
        setTime2 = time2Id.floatSetter(),
        setMod2 = mod2Id.floatSetter(),
        setFeedback = feedbackId.floatSetter(),
        setMix = mixId.floatSetter(),
        setSource = modSourceId.boolSetter(),
        setWaveform = waveformId.boolSetter()
    )
    // Control changes -> DelayIntent
    private val controlIntents = merge(
        time1Id.map { DelayIntent.Time1(it.asFloat()) },
        time2Id.map { DelayIntent.Time2(it.asFloat()) },
        mod1Id.map { DelayIntent.Mod1(it.asFloat()) },
        mod2Id.map { DelayIntent.Mod2(it.asFloat()) },
        feedbackId.map { DelayIntent.Feedback(it.asFloat()) },
        mixId.map { DelayIntent.Mix(it.asFloat()) },
        modSourceId.map { DelayIntent.Source(it.asBoolean()) },
        waveformId.map { DelayIntent.Waveform(it.asBoolean()) }
    )

    override val stateFlow: StateFlow<DelayUiState> =
        controlIntents
            .scan(DelayUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = DelayUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DelayUiState, intent: DelayIntent): DelayUiState =
        when (intent) {
            is DelayIntent.Time1 -> state.copy(time1 = intent.value)
            is DelayIntent.Time2 -> state.copy(time2 = intent.value)
            is DelayIntent.Mod1 -> state.copy(mod1 = intent.value)
            is DelayIntent.Mod2 -> state.copy(mod2 = intent.value)
            is DelayIntent.Feedback -> state.copy(feedback = intent.value)
            is DelayIntent.Mix -> state.copy(mix = intent.value)
            is DelayIntent.Source -> state.copy(isLfoSource = intent.isLfo)
            is DelayIntent.Waveform -> state.copy(isTriangleWave = intent.isTriangle)
        }

    companion object {
        fun previewFeature(state: DelayUiState = DelayUiState()): DelayFeature =
            object : DelayFeature {
                override val stateFlow: StateFlow<DelayUiState> = MutableStateFlow(state)
                override val actions: DelayPanelActions = DelayPanelActions.EMPTY
            }

        @Composable
        fun feature(): DelayFeature =
            synthFeature<DelayViewModel, DelayFeature>()
    }
}
