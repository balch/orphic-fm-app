package org.balch.orpheus.features.distortion

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.core.synthViewModel

@Immutable
data class DistortionUiState(
    val drive: Float = 0.0f,
    val volume: Float = 0.7f,
    val mix: Float = 0.5f,
    val peak: Float = 0.0f,
    val mode: StereoMode = StereoMode.VOICE_PAN,
    val masterPan: Float = 0f
)

@Immutable
data class DistortionPanelActions(
    val setDrive: (Float) -> Unit,
    val setVolume: (Float) -> Unit,
    val setMix: (Float) -> Unit,
    val setMode: (StereoMode) -> Unit,
    val setMasterPan: (Float) -> Unit,
) {
    companion object {
        val EMPTY = DistortionPanelActions({}, {}, {}, {}, {})
    }
}

/** User intents for the Distortion panel. */
private sealed interface DistortionIntent {
    data class Drive(val value: Float) : DistortionIntent
    data class Volume(val value: Float) : DistortionIntent
    data class Mix(val value: Float) : DistortionIntent
    data class Peak(val value: Float) : DistortionIntent
    data class SetMode(val mode: StereoMode) : DistortionIntent
    data class SetMasterPan(val pan: Float) : DistortionIntent
}

interface DistortionFeature : SynthFeature<DistortionUiState, DistortionPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.DISTORTION
            override val title = "Drive & Output"

            override val markdown = """
        Drive/distortion stage and master output controls. Includes drive amount, distortion mix, master volume, and master pan.

        ## Controls
        - **DRIVE**: Amount of distortion/overdrive applied to the signal.
        - **MIX**: Dry/wet blend between clean and distorted signal.
        - **MASTER VOL**: Master output volume level.
        - **MASTER PAN**: Master stereo panning position.

        ## Tips
        - Keep DRIVE low for subtle warmth, or push it high for aggressive distortion.
        - Use MIX to blend distorted signal with the clean original for parallel distortion.
            """.trimIndent()

            override val portControlKeys = mapOf(
                DistortionSymbol.DRIVE.controlId.key to "Distortion/overdrive amount",
                DistortionSymbol.MIX.controlId.key to "Dry/wet blend (clean vs distorted)",
                StereoSymbol.MASTER_VOL.controlId.key to "Master output volume",
                StereoSymbol.MASTER_PAN.controlId.key to "Master stereo pan position",
            )
        }
    }
}

/**
 * ViewModel for the Distortion panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for port-based engine interactions.
 * Keeps SynthEngine dependency for peakFlow monitoring and StereoMode (non-port state).
 */
@Inject
@ViewModelKey(DistortionViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*,*>>())
class DistortionViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DistortionFeature {

    // Control flows for plugin ports
    private val driveId = synthController.controlFlow(DistortionSymbol.DRIVE.controlId)
    private val mixId = synthController.controlFlow(DistortionSymbol.MIX.controlId)
    private val volumeId = synthController.controlFlow(StereoSymbol.MASTER_VOL.controlId)
    private val masterPanId = synthController.controlFlow(StereoSymbol.MASTER_PAN.controlId)

    override val actions = DistortionPanelActions(
        setDrive = driveId.floatSetter(),
        setVolume = volumeId.floatSetter(),
        setMix = mixId.floatSetter(),
        setMode = ::setMode,
        setMasterPan = masterPanId.floatSetter()
    )

    // UI-only intents (non-port state: StereoMode)
    private val uiIntents = MutableSharedFlow<DistortionIntent>(extraBufferCapacity = 64)

    // Peak level updates from engine (monitoring, not a port)
    private val peakIntents = engine.peakFlow.map { DistortionIntent.Peak(it) }

    // Port-based control changes -> intents
    private val controlIntents = merge(
        driveId.map { DistortionIntent.Drive(it.asFloat()) },
        mixId.map { DistortionIntent.Mix(it.asFloat()) },
        volumeId.map { DistortionIntent.Volume(it.asFloat()) },
        masterPanId.map { DistortionIntent.SetMasterPan(it.asFloat()) }
    )

    override val stateFlow: StateFlow<DistortionUiState> =
        merge(controlIntents, uiIntents, peakIntents)
            .scan(DistortionUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = DistortionUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DistortionUiState, intent: DistortionIntent): DistortionUiState =
        when (intent) {
            is DistortionIntent.Drive -> state.copy(drive = intent.value)
            is DistortionIntent.Volume -> state.copy(volume = intent.value)
            is DistortionIntent.Mix -> state.copy(mix = intent.value)
            is DistortionIntent.Peak -> state.copy(peak = intent.value)
            is DistortionIntent.SetMode -> state.copy(mode = intent.mode)
            is DistortionIntent.SetMasterPan -> state.copy(masterPan = intent.pan)
        }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setMode(mode: StereoMode) {
        engine.setStereoMode(mode)
        uiIntents.tryEmit(DistortionIntent.SetMode(mode))
    }

    companion object {
        fun previewFeature(state: DistortionUiState = DistortionUiState()): DistortionFeature =
            object : DistortionFeature {
                override val stateFlow: StateFlow<DistortionUiState> = MutableStateFlow(state)
                override val actions: DistortionPanelActions = DistortionPanelActions.EMPTY
            }

        @Composable
        fun feature(): DistortionFeature =
            synthViewModel<DistortionViewModel, DistortionFeature>()
    }
}
