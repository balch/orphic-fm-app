package org.balch.orpheus.features.grains

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.controller.boolSetter
import org.balch.orpheus.core.controller.enumSetter
import org.balch.orpheus.core.controller.floatSetter
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.PortValue.BoolValue
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.plugins.grains.engine.GrainsMode

@Immutable
data class GrainsUiState(
    val position: Float = 0.2f,  // Delay Time / Loop Length
    val size: Float = 0.5f,      // Grain Size / Diffusion
    val pitch: Float = 0.0f,     // Pitch Shifting (semitones/ratio)
    val density: Float = 0.5f,   // Feedback / Grain Overlap
    val texture: Float = 0.5f,   // Filter (LP/HP)
    val dryWet: Float = 0.5f,    // Mix (0=dry, 1=wet, 0.5=50/50)
    val freeze: Boolean = false, // Loop/Freeze
    val trigger: Boolean = false, // Trigger
    val mode: GrainsMode = GrainsMode.GRANULAR // Processing mode
)

@Immutable
data class GrainsPanelActions(
    val setPosition: (Float) -> Unit,
    val setSize: (Float) -> Unit,
    val setPitch: (Float) -> Unit,
    val setDensity: (Float) -> Unit,
    val setTexture: (Float) -> Unit,
    val setDryWet: (Float) -> Unit,
    val setFreeze: (Boolean) -> Unit,
    val trigger: () -> Unit,
    val setMode: (GrainsMode) -> Unit
) {
    companion object Companion {
        val EMPTY = GrainsPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** User intents for the Grains panel. */
private sealed interface GrainsIntent {
    data class Position(val value: Float) : GrainsIntent
    data class Size(val value: Float) : GrainsIntent
    data class Pitch(val value: Float) : GrainsIntent
    data class Density(val value: Float) : GrainsIntent
    data class Texture(val value: Float) : GrainsIntent
    data class DryWet(val value: Float) : GrainsIntent
    data class Freeze(val frozen: Boolean) : GrainsIntent
    data class Trigger(val active: Boolean) : GrainsIntent
    data class Mode(val mode: GrainsMode) : GrainsIntent
}

interface GrainsFeature : SynthFeature<GrainsUiState, GrainsPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.GRAINS
            override val title = "Grains"

            override val markdown = """
        Granular audio processor. Captures and plays back fragments of sound. Controls position, size, density, texture, pitch, freeze, and dry/wet.

        ## Controls
        - **POSITION**: Playback position in the audio buffer / delay time.
        - **SIZE**: Grain size / diffusion amount.
        - **PITCH**: Pitch shifting amount (semitones).
        - **DENSITY**: Grain overlap / feedback amount.
        - **TEXTURE**: Filter character (low-pass to high-pass).
        - **DRY/WET**: Mix between dry input and granular output.

        ## Switches
        - **FREEZE**: Captures and loops the current buffer contents.
        - **MODE**: Processing mode (Granular, Stretch, Looping Delay, Spectral).

        ## Tips
        - Use FREEZE to capture a moment, then sweep POSITION and SIZE to explore the frozen sound.
        - Low DENSITY with high TEXTURE creates shimmering, sparse textures.
            """.trimIndent()

            override val portControlKeys = mapOf(
                GrainsSymbol.POSITION.controlId.key to "Playback position / delay time",
                GrainsSymbol.SIZE.controlId.key to "Grain size / diffusion",
                GrainsSymbol.PITCH.controlId.key to "Pitch shifting (semitones)",
                GrainsSymbol.DENSITY.controlId.key to "Grain overlap / feedback",
                GrainsSymbol.TEXTURE.controlId.key to "Filter character (LP to HP)",
                GrainsSymbol.DRY_WET.controlId.key to "Dry/wet mix",
                GrainsSymbol.FREEZE.controlId.key to "Freeze/loop the audio buffer",
                GrainsSymbol.MODE.controlId.key to "Processing mode selection",
            )
        }
    }
}

/**
 * ViewModel for the Grains Texture Synthesizer panel.
 *
 * Uses MVI pattern with SynthController.controlFlow() for all engine interactions.
 */
@Inject
@ViewModelKey(GrainsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ContributesIntoSet(AppScope::class, binding = binding<SynthFeature<*, *>>())
class GrainsViewModel(
    synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel(), GrainsFeature {

    // Control flows for Grains plugin ports
    private val positionId = synthController.controlFlow(GrainsSymbol.POSITION.controlId)
    private val sizeId = synthController.controlFlow(GrainsSymbol.SIZE.controlId)
    private val pitchId = synthController.controlFlow(GrainsSymbol.PITCH.controlId)
    private val densityId = synthController.controlFlow(GrainsSymbol.DENSITY.controlId)
    private val textureId = synthController.controlFlow(GrainsSymbol.TEXTURE.controlId)
    private val dryWetId = synthController.controlFlow(GrainsSymbol.DRY_WET.controlId)
    private val freezeId = synthController.controlFlow(GrainsSymbol.FREEZE.controlId)
    private val triggerId = synthController.controlFlow(GrainsSymbol.TRIGGER.controlId)
    private val modeId = synthController.controlFlow(GrainsSymbol.MODE.controlId)

    override val actions = GrainsPanelActions(
        setPosition = positionId.floatSetter(),
        setSize = sizeId.floatSetter(),
        setPitch = pitchId.floatSetter(),
        setDensity = densityId.floatSetter(),
        setTexture = textureId.floatSetter(),
        setDryWet = dryWetId.floatSetter(),
        setFreeze = freezeId.boolSetter(),
        trigger = ::trigger,
        setMode = modeId.enumSetter()
    )

    // Control changes -> GrainsIntent
    private val controlIntents = merge(
        positionId.map { GrainsIntent.Position(it.asFloat()) },
        sizeId.map { GrainsIntent.Size(it.asFloat()) },
        pitchId.map { GrainsIntent.Pitch(it.asFloat()) },
        densityId.map { GrainsIntent.Density(it.asFloat()) },
        textureId.map { GrainsIntent.Texture(it.asFloat()) },
        dryWetId.map { GrainsIntent.DryWet(it.asFloat()) },
        freezeId.map { GrainsIntent.Freeze(it.asBoolean()) },
        triggerId.map { GrainsIntent.Trigger(it.asBoolean()) },
        modeId.map {
            val modes = GrainsMode.entries
            val index = it.asInt().coerceIn(0, modes.size - 1)
            GrainsIntent.Mode(modes[index])
        }
    )

    override val stateFlow: StateFlow<GrainsUiState> =
        controlIntents
            .scan(GrainsUiState()) { state, intent ->
                reduce(state, intent)
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = this.sharingStrategy,
                initialValue = GrainsUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: GrainsUiState, intent: GrainsIntent): GrainsUiState =
        when (intent) {
            is GrainsIntent.Position -> state.copy(position = intent.value)
            is GrainsIntent.Size -> state.copy(size = intent.value)
            is GrainsIntent.Pitch -> state.copy(pitch = intent.value)
            is GrainsIntent.Density -> state.copy(density = intent.value)
            is GrainsIntent.Texture -> state.copy(texture = intent.value)
            is GrainsIntent.DryWet -> state.copy(dryWet = intent.value)
            is GrainsIntent.Freeze -> state.copy(freeze = intent.frozen)
            is GrainsIntent.Mode -> state.copy(mode = intent.mode)
            is GrainsIntent.Trigger -> state.copy(trigger = intent.active)
        }

    fun trigger() {
        triggerId.value = BoolValue(true)
        viewModelScope.launch(dispatcherProvider.default) {
            kotlinx.coroutines.delay(20)
            triggerId.value = BoolValue(false)
        }
    }

    companion object Companion {
        fun previewFeature(state: GrainsUiState = GrainsUiState()): GrainsFeature =
            object : GrainsFeature {
                override val stateFlow: StateFlow<GrainsUiState> = MutableStateFlow(state)
                override val actions: GrainsPanelActions = GrainsPanelActions.EMPTY
            }

        @Composable
        fun feature(): GrainsFeature =
            synthViewModel<GrainsViewModel, GrainsFeature>()
    }
}
