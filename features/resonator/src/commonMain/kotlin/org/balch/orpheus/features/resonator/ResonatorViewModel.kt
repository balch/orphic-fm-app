package org.balch.orpheus.features.resonator

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.synthViewModel

enum class ResonatorMode(val displayName: String) {
    MODAL("Bar"),
    STRING("String"),
    SYMPATHETIC("Sitar")
}

/**
 * UI state for the Rings Resonator.
 */
@Immutable
data class ResonatorUiState(
    val mode: ResonatorMode = ResonatorMode.MODAL,
    val targetMix: Float = 0.5f,     // 0=Drums only, 0.5=Both, 1=Synth only
    val snapBack: Boolean = false,   // Whether fader snaps back to center on release
    val structure: Float = 0.25f,    // Material/inharmonicity (0-1)
    val brightness: Float = 0.5f,    // High freq content (0-1)
    val damping: Float = 0.3f,       // Decay time (0-1)
    val position: Float = 0.5f,      // Excitation point (0-1)
    val mix: Float = 0f              // Dry/wet (0-1)
)

/**
 * Actions for controlling the resonator.
 */
@Immutable
data class ResonatorPanelActions(
    val setMode: (ResonatorMode) -> Unit,
    val setTargetMix: (Float) -> Unit,
    val setSnapBack: (Boolean) -> Unit,
    val setStructure: (Float) -> Unit,
    val setBrightness: (Float) -> Unit,
    val setDamping: (Float) -> Unit,
    val setPosition: (Float) -> Unit,
    val setMix: (Float) -> Unit
) {
    companion object {
        val EMPTY = ResonatorPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Resonator panel. */
private sealed interface ResonatorIntent {
    data class SetMode(val mode: ResonatorMode) : ResonatorIntent
    data class Structure(val value: Float) : ResonatorIntent
    data class Brightness(val value: Float) : ResonatorIntent
    data class Damping(val value: Float) : ResonatorIntent
    data class Position(val value: Float) : ResonatorIntent
    data class Mix(val value: Float) : ResonatorIntent
    data class TargetMix(val value: Float) : ResonatorIntent
    data class SnapBack(val active: Boolean) : ResonatorIntent
}

typealias ResonatorFeature = SynthFeature<ResonatorUiState, ResonatorPanelActions>

/**
 * ViewModel for the Rings Resonator panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@ViewModelKey(ResonatorViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ResonatorViewModel @Inject constructor(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
) : ViewModel(), ResonatorFeature {

    // Control flows for Resonator plugin ports
    private val modeId = synthController.controlFlow(ResonatorSymbol.MODE.controlId)
    private val targetMixId = synthController.controlFlow(ResonatorSymbol.TARGET_MIX.controlId)
    private val snapBackId = synthController.controlFlow(ResonatorSymbol.SNAP_BACK.controlId)
    private val structureId = synthController.controlFlow(ResonatorSymbol.STRUCTURE.controlId)
    private val brightnessId = synthController.controlFlow(ResonatorSymbol.BRIGHTNESS.controlId)
    private val dampingId = synthController.controlFlow(ResonatorSymbol.DAMPING.controlId)
    private val positionId = synthController.controlFlow(ResonatorSymbol.POSITION.controlId)
    private val mixId = synthController.controlFlow(ResonatorSymbol.MIX.controlId)

    override val actions = ResonatorPanelActions(
        setMode = modeId.enumSetter(),
        setTargetMix = targetMixId.floatSetter(),
        setSnapBack = snapBackId.boolSetter(),
        setStructure = structureId.floatSetter(),
        setBrightness = brightnessId.floatSetter(),
        setDamping = dampingId.floatSetter(),
        setPosition = positionId.floatSetter(),
        setMix = mixId.floatSetter()
    )

    // Control changes -> ResonatorIntent
    private val controlIntents = merge(
        modeId.map {
            val modes = ResonatorMode.entries
            val index = it.asInt().coerceIn(0, modes.size - 1)
            ResonatorIntent.SetMode(modes[index])
        },
        targetMixId.map { ResonatorIntent.TargetMix(it.asFloat()) },
        snapBackId.map { ResonatorIntent.SnapBack(it.asBoolean()) },
        structureId.map { ResonatorIntent.Structure(it.asFloat()) },
        brightnessId.map { ResonatorIntent.Brightness(it.asFloat()) },
        dampingId.map { ResonatorIntent.Damping(it.asFloat()) },
        positionId.map { ResonatorIntent.Position(it.asFloat()) },
        mixId.map { ResonatorIntent.Mix(it.asFloat()) }
    )

    override val stateFlow: StateFlow<ResonatorUiState> =
        controlIntents
            .scan(ResonatorUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = ResonatorUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: ResonatorUiState, intent: ResonatorIntent): ResonatorUiState =
        when (intent) {
            is ResonatorIntent.SetMode -> state.copy(mode = intent.mode)
            is ResonatorIntent.Structure -> state.copy(structure = intent.value)
            is ResonatorIntent.Brightness -> state.copy(brightness = intent.value)
            is ResonatorIntent.Damping -> state.copy(damping = intent.value)
            is ResonatorIntent.Position -> state.copy(position = intent.value)
            is ResonatorIntent.Mix -> state.copy(mix = intent.value)
            is ResonatorIntent.TargetMix -> state.copy(targetMix = intent.value)
            is ResonatorIntent.SnapBack -> state.copy(snapBack = intent.active)
        }

    companion object {
        fun previewFeature(state: ResonatorUiState = ResonatorUiState()): ResonatorFeature =
            object : ResonatorFeature {
                override val stateFlow: StateFlow<ResonatorUiState> = MutableStateFlow(state)
                override val actions: ResonatorPanelActions = ResonatorPanelActions.EMPTY
            }

        @Composable
        fun feature(): ResonatorFeature =
            synthViewModel<ResonatorViewModel, ResonatorFeature>()
    }
}
