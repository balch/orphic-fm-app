package org.balch.orpheus.features.reverb

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.synthViewModel

@Immutable
data class ReverbUiState(
    val amount: Float = 0.3f,
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

typealias ReverbFeature = SynthFeature<ReverbUiState, ReverbPanelActions>

@ViewModelKey(ReverbViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReverbViewModel @Inject constructor(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), ReverbFeature {

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
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
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
            synthViewModel<ReverbViewModel, ReverbFeature>()
    }
}
