package org.balch.orpheus.features.grains

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.plugins.grains.engine.GrainsMode
import kotlin.math.roundToInt

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
    data class Position(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class Size(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class Pitch(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class Density(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class Texture(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class DryWet(val value: Float, val fromSequencer: Boolean = false) : GrainsIntent
    data class Freeze(val frozen: Boolean, val fromSequencer: Boolean = false) : GrainsIntent
    data class Trigger(val active: Boolean, val fromSequencer: Boolean = false) : GrainsIntent
    data class Mode(val mode: GrainsMode) : GrainsIntent
    data class Restore(val state: GrainsUiState) : GrainsIntent
}

typealias GrainsFeature = SynthFeature<GrainsUiState, GrainsPanelActions>

@Inject
@ViewModelKey(GrainsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class GrainsViewModel(
    private val engine: SynthEngine,
    private val synthController: SynthController,
    presetLoader: org.balch.orpheus.core.presets.PresetLoader,
    dispatcherProvider: DispatcherProvider,
) : ViewModel(), GrainsFeature {

    override val actions = GrainsPanelActions(
        setPosition = ::setPosition,
        setSize = ::setSize,
        setPitch = ::setPitch,
        setDensity = ::setDensity,
        setTexture = ::setTexture,
        setDryWet = ::setDryWet,
        setFreeze = ::setFreeze,
        trigger = ::trigger,
        setMode = ::setMode
    )

    // User intents flow
    private val _userIntents = MutableSharedFlow<GrainsIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> GrainsIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        GrainsIntent.Restore(
            GrainsUiState(
                position = preset.grainsPosition,
                size = preset.grainsSize,
                pitch = preset.grainsPitch,
                density = preset.grainsDensity,
                texture = preset.grainsTexture,
                dryWet = preset.grainsDryWet,
                freeze = preset.grainsFreeze,
                mode = GrainsMode.entries.getOrElse(preset.grainsMode) { GrainsMode.GRANULAR }
            )
        )
    }

    // Control changes -> GrainsIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        when (event.controlId) {
            ControlIds.GRAINS_POSITION -> GrainsIntent.Position(event.value, fromSequencer)
            ControlIds.GRAINS_SIZE -> GrainsIntent.Size(event.value, fromSequencer)
            ControlIds.GRAINS_PITCH -> GrainsIntent.Pitch(event.value, fromSequencer)
            ControlIds.GRAINS_DENSITY -> GrainsIntent.Density(event.value, fromSequencer)
            ControlIds.GRAINS_TEXTURE -> GrainsIntent.Texture(event.value, fromSequencer)
            ControlIds.GRAINS_DRY_WET -> GrainsIntent.DryWet(event.value, fromSequencer)
            ControlIds.GRAINS_FREEZE -> GrainsIntent.Freeze(event.value > 0.5f, fromSequencer)
            ControlIds.GRAINS_MODE -> {
                val modes = GrainsMode.entries
                val index = (event.value * (modes.size - 1)).roundToInt().coerceIn(0, modes.size - 1)
                GrainsIntent.Mode(modes[index])
            }
            ControlIds.GRAINS_TRIGGER -> GrainsIntent.Trigger(event.value > 0.5f, fromSequencer)
            else -> null
        }
    }

    override val stateFlow: StateFlow<GrainsUiState> =
        merge(_userIntents, presetIntents, controlIntents)
            .scan(GrainsUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
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
            is GrainsIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: GrainsUiState, intent: GrainsIntent) {
        when (intent) {
            is GrainsIntent.Position -> if (!intent.fromSequencer) engine.setGrainsPosition(intent.value)
            is GrainsIntent.Size -> if (!intent.fromSequencer) engine.setGrainsSize(intent.value)
            is GrainsIntent.Pitch -> if (!intent.fromSequencer) engine.setGrainsPitch(intent.value)
            is GrainsIntent.Density -> if (!intent.fromSequencer) engine.setGrainsDensity(intent.value)
            is GrainsIntent.Texture -> if (!intent.fromSequencer) engine.setGrainsTexture(intent.value)
            is GrainsIntent.DryWet -> if (!intent.fromSequencer) engine.setGrainsDryWet(intent.value)
            is GrainsIntent.Freeze -> if (!intent.fromSequencer) engine.setGrainsFreeze(intent.frozen)
            is GrainsIntent.Mode -> engine.setGrainsMode(intent.mode.ordinal)
            is GrainsIntent.Trigger -> {
                 if (!intent.fromSequencer) {
                     engine.setGrainsTrigger(intent.active)
                 }
            }
            is GrainsIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: GrainsUiState) {
        engine.setGrainsPosition(state.position)
        engine.setGrainsSize(state.size)
        engine.setGrainsPitch(state.pitch)
        engine.setGrainsDensity(state.density)
        engine.setGrainsTexture(state.texture)
        engine.setGrainsDryWet(state.dryWet)
        engine.setGrainsFreeze(state.freeze)
        engine.setGrainsMode(state.mode.ordinal)
        // Trigger is transient, no need to restore
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setPosition(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Position(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_POSITION, value, ControlEventOrigin.UI)
    }

    fun setSize(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Size(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_SIZE, value, ControlEventOrigin.UI)
    }

    fun setPitch(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Pitch(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_PITCH, value, ControlEventOrigin.UI)
    }

    fun setDensity(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Density(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_DENSITY, value, ControlEventOrigin.UI)
    }

    fun setTexture(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Texture(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_TEXTURE, value, ControlEventOrigin.UI)
    }

    fun setDryWet(value: Float) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.DryWet(value, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_DRY_WET, value, ControlEventOrigin.UI)
    }

    fun setFreeze(frozen: Boolean) {
        val fromSequencer = false
        _userIntents.tryEmit(GrainsIntent.Freeze(frozen, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_FREEZE, if (frozen) 1f else 0f, ControlEventOrigin.UI)
    }

    fun setMode(mode: GrainsMode) {
        _userIntents.tryEmit(GrainsIntent.Mode(mode))
        val value = mode.ordinal.toFloat() / 2f
        synthController.emitControlChange(ControlIds.GRAINS_MODE, value, ControlEventOrigin.UI)
    }

    fun trigger() {
        val fromSequencer = false
        
        // Emit Start
        _userIntents.tryEmit(GrainsIntent.Trigger(true, fromSequencer))
        synthController.emitControlChange(ControlIds.GRAINS_TRIGGER, 1f, ControlEventOrigin.UI)
        
        // Emit End after delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(20)
            _userIntents.tryEmit(GrainsIntent.Trigger(false, fromSequencer))
            synthController.emitControlChange(ControlIds.GRAINS_TRIGGER, 0f, ControlEventOrigin.UI)
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

