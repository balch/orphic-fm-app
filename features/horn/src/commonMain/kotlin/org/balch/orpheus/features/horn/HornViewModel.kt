package org.balch.orpheus.features.horn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.symbols.HornSymbol

@Immutable
data class HornUiState(
    val speed: Float = 0.5f,
    val ratio: Float = 0.5f,
    val depth: Float = 0.5f,
    val amount: Float = 0.5f,
    val mix: Float = 0.0f,
    val brake: Boolean = false
)

@Immutable
data class HornPanelActions(
    val setSpeed: (Float) -> Unit,
    val setRatio: (Float) -> Unit,
    val setDepth: (Float) -> Unit,
    val setAmount: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setBrake: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = HornPanelActions({}, {}, {}, {}, {}, {})
    }
}

private sealed interface HornIntent {
    data class Speed(val value: Float) : HornIntent
    data class Ratio(val value: Float) : HornIntent
    data class Depth(val value: Float) : HornIntent
    data class Amount(val value: Float) : HornIntent
    data class Mix(val value: Float) : HornIntent
    data class Brake(val enabled: Boolean) : HornIntent
}

interface HornFeature : SynthFeature<HornUiState, HornPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.HORN
            override val title = "Horn"

            override val markdown = """
        Rotating speaker cabinet effect that adds movement and modulation to sounds.

        ## Controls
        - **SPEED**: Rotation speed of the simulated speaker cabinet. Lower values create slow, sweeping modulation; higher values produce fast tremolo-like effects.
        - **RATIO**: Balance between the horn (high-frequency) and rotor (low-frequency) speaker speeds. Adjusts how the two rotors track each other.
        - **DEPTH**: Intensity of the rotary modulation. Higher values produce more pronounced pitch and amplitude variation.
        - **AMOUNT**: Overall effect intensity. Controls how strongly the rotating speaker character is applied to the signal.
        - **MIX**: Dry/wet blend. At 0, the effect is bypassed. At 1, only the processed signal is heard.
        - **BRAKE**: When enabled, gradually slows the rotation to a stop, simulating the effect of disengaging the motor.

        ## Tips
        - Set SPEED low and DEPTH high for a classic slow-rotation organ effect.
        - Gradually increase SPEED mid-performance for a dramatic build.
        - Use BRAKE to freeze the rotation for a static chorus-like effect.
        - RATIO controls the character of the effect — experiment with uneven ratios for more complex motion.
    """.trimIndent()

            override val portControlKeys = mapOf(
                HornSymbol.SPEED.controlId.key to "Rotation speed of the speaker cabinet",
                HornSymbol.RATIO.controlId.key to "Horn to rotor speed ratio",
                HornSymbol.DEPTH.controlId.key to "Intensity of rotary modulation",
                HornSymbol.AMOUNT.controlId.key to "Overall effect intensity",
                HornSymbol.MIX.controlId.key to "Dry/wet blend",
                HornSymbol.BRAKE.controlId.key to "Slow rotation to a stop",
            )
        }
    }
}

@Inject
@ClassKey(HornViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class HornViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : HornFeature {

    private val speedFlow = synthController.controlFlow(HornSymbol.SPEED.controlId)
    private val ratioFlow = synthController.controlFlow(HornSymbol.RATIO.controlId)
    private val depthFlow = synthController.controlFlow(HornSymbol.DEPTH.controlId)
    private val amountFlow = synthController.controlFlow(HornSymbol.AMOUNT.controlId)
    private val mixFlow = synthController.controlFlow(HornSymbol.MIX.controlId)
    private val brakeFlow = synthController.controlFlow(HornSymbol.BRAKE.controlId)

    override val actions = HornPanelActions(
        setSpeed = speedFlow.floatSetter(),
        setRatio = ratioFlow.floatSetter(),
        setDepth = depthFlow.floatSetter(),
        setAmount = amountFlow.floatSetter(),
        setMix = mixFlow.floatSetter(),
        setBrake = brakeFlow.boolSetter()
    )

    private val controlIntents = merge(
        speedFlow.map { HornIntent.Speed(it.asFloat()) },
        ratioFlow.map { HornIntent.Ratio(it.asFloat()) },
        depthFlow.map { HornIntent.Depth(it.asFloat()) },
        amountFlow.map { HornIntent.Amount(it.asFloat()) },
        mixFlow.map { HornIntent.Mix(it.asFloat()) },
        brakeFlow.map { HornIntent.Brake(it.asBoolean()) }
    )

    override val stateFlow: StateFlow<HornUiState> =
        controlIntents
            .scan(HornUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = HornUiState()
            )

    private fun reduce(state: HornUiState, intent: HornIntent): HornUiState =
        when (intent) {
            is HornIntent.Speed -> state.copy(speed = intent.value)
            is HornIntent.Ratio -> state.copy(ratio = intent.value)
            is HornIntent.Depth -> state.copy(depth = intent.value)
            is HornIntent.Amount -> state.copy(amount = intent.value)
            is HornIntent.Mix -> state.copy(mix = intent.value)
            is HornIntent.Brake -> state.copy(brake = intent.enabled)
        }

    companion object {
        fun previewFeature(state: HornUiState = HornUiState()): HornFeature =
            object : HornFeature {
                override val stateFlow: StateFlow<HornUiState> = MutableStateFlow(state)
                override val actions: HornPanelActions = HornPanelActions.EMPTY
            }

        @Composable
        fun feature(): HornFeature =
            synthFeature<HornViewModel, HornFeature>()
    }
}
