package org.balch.orpheus.features.reverb

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
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.synthFeature

@Immutable
data class ReverbUiState(
    val amount: Float = 0.0f,
    val time: Float = 0.5f,
    val damping: Float = 0.7f,
    val diffusion: Float = 0.625f
)

@Immutable
data class ReverbPanelActions(
    val setAmount: (Float) -> Unit,
    val setTime: (Float) -> Unit,
    val setDamping: (Float) -> Unit,
    val setDiffusion: (Float) -> Unit
) {
    companion object {
        val EMPTY = ReverbPanelActions({}, {}, {}, {})
    }
}

private sealed interface ReverbIntent {
    data class Amount(val value: Float) : ReverbIntent
    data class Time(val value: Float) : ReverbIntent
    data class Damping(val value: Float) : ReverbIntent
    data class Diffusion(val value: Float) : ReverbIntent
}

interface ReverbFeature : SynthFeature<ReverbUiState, ReverbPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.REVERB
            override val title = "Reverb"

            override val markdown = """
        Algorithmic reverb for adding space and depth to sounds. Ranges from tight rooms to vast cathedral ambiences.

        ## Controls
        - **AMOUNT**: Overall reverb intensity / wet level. Higher values push more signal into the reverb.
        - **TIME**: Reverb tail length. Low values give short, tight rooms; high values create expansive halls.
        - **DAMPING**: High-frequency absorption. Low damping = bright, metallic reflections. High damping = warm, dark tails.
        - **DIFFUSION**: Density of the reverb reflections. Low diffusion creates audible individual echoes; high diffusion produces a smooth, lush wash.

        ## Tips
        - For ambient pads, use high TIME and high DIFFUSION with moderate DAMPING.
        - For percussive sounds, keep TIME short and AMOUNT moderate for a natural room feel.
        - Combine with Delay for huge, atmospheric soundscapes.
        - High DAMPING with long TIME creates warm, pad-like tails.
    """.trimIndent()

            override val portControlKeys = mapOf(
                ReverbSymbol.AMOUNT.controlId.key to "Reverb intensity / wet level",
                ReverbSymbol.TIME.controlId.key to "Reverb tail length",
                ReverbSymbol.DAMPING.controlId.key to "High-frequency absorption in reverb tail",
                ReverbSymbol.DIFFUSION.controlId.key to "Density of reverb reflections",
            )
        }
    }
}

@Inject
@ClassKey(ReverbViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class ReverbViewModel(
    synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : ReverbFeature {

    private val amountFlow = synthController.controlFlow(ReverbSymbol.AMOUNT.controlId)
    private val timeFlow = synthController.controlFlow(ReverbSymbol.TIME.controlId)
    private val dampingFlow = synthController.controlFlow(ReverbSymbol.DAMPING.controlId)
    private val diffusionFlow = synthController.controlFlow(ReverbSymbol.DIFFUSION.controlId)

    override val actions = ReverbPanelActions(
        setAmount = amountFlow.floatSetter(),
        setTime = timeFlow.floatSetter(),
        setDamping = dampingFlow.floatSetter(),
        setDiffusion = diffusionFlow.floatSetter()
    )

    private val controlIntents = merge(
        amountFlow.map { ReverbIntent.Amount(it.asFloat()) },
        timeFlow.map { ReverbIntent.Time(it.asFloat()) },
        dampingFlow.map { ReverbIntent.Damping(it.asFloat()) },
        diffusionFlow.map { ReverbIntent.Diffusion(it.asFloat()) }
    )

    override val stateFlow: StateFlow<ReverbUiState> =
        controlIntents
            .scan(ReverbUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = ReverbUiState()
            )

    private fun reduce(state: ReverbUiState, intent: ReverbIntent): ReverbUiState =
        when (intent) {
            is ReverbIntent.Amount -> state.copy(amount = intent.value)
            is ReverbIntent.Time -> state.copy(time = intent.value)
            is ReverbIntent.Damping -> state.copy(damping = intent.value)
            is ReverbIntent.Diffusion -> state.copy(diffusion = intent.value)
        }

    companion object {
        fun previewFeature(state: ReverbUiState = ReverbUiState()): ReverbFeature =
            object : ReverbFeature {
                override val stateFlow: StateFlow<ReverbUiState> = MutableStateFlow(state)
                override val actions: ReverbPanelActions = ReverbPanelActions.EMPTY
            }

        @Composable
        fun feature(): ReverbFeature =
            synthFeature<ReverbViewModel, ReverbFeature>()
    }
}
