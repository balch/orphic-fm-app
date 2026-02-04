package org.balch.orpheus.features.drum

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
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.drumBdDecay
import org.balch.orpheus.core.presets.drumBdFrequency
import org.balch.orpheus.core.presets.drumBdP4
import org.balch.orpheus.core.presets.drumBdP5
import org.balch.orpheus.core.presets.drumBdPitchSource
import org.balch.orpheus.core.presets.drumBdTone
import org.balch.orpheus.core.presets.drumBdTriggerSource
import org.balch.orpheus.core.presets.drumHhDecay
import org.balch.orpheus.core.presets.drumHhFrequency
import org.balch.orpheus.core.presets.drumHhP4
import org.balch.orpheus.core.presets.drumHhPitchSource
import org.balch.orpheus.core.presets.drumHhTone
import org.balch.orpheus.core.presets.drumHhTriggerSource
import org.balch.orpheus.core.presets.drumSdDecay
import org.balch.orpheus.core.presets.drumSdFrequency
import org.balch.orpheus.core.presets.drumSdP4
import org.balch.orpheus.core.presets.drumSdPitchSource
import org.balch.orpheus.core.presets.drumSdTone
import org.balch.orpheus.core.presets.drumSdTriggerSource
import org.balch.orpheus.core.presets.drumsBypass
import org.balch.orpheus.core.routing.ControlEventOrigin
import org.balch.orpheus.core.routing.SynthController
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.core.triggers.DrumTriggerSource
import kotlin.math.roundToInt

@Immutable
data class DrumUiState(
    // Bass Drum
    val bdFrequency: Float = 0.3f, // Maps to ~50Hz
    val bdTone: Float = 0.5f,
    val bdDecay: Float = 0.5f,
    val bdP4: Float = 0.5f,  // AFM (Attack FM)
    val bdP5: Float = 0.5f,  // Self FM
    val bdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val bdPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Snare Drum
    val sdFrequency: Float = 0.4f, // Maps to ~180Hz
    val sdTone: Float = 0.5f,
    val sdDecay: Float = 0.5f,
    val sdP4: Float = 0.5f,  // Snappiness
    val sdTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val sdPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Hi-Hat
    val hhFrequency: Float = 0.6f, // Maps to ~400Hz
    val hhTone: Float = 0.5f,
    val hhDecay: Float = 0.5f,
    val hhP4: Float = 0.5f,  // Noisiness
    val hhTriggerSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    val hhPitchSource: DrumTriggerSource = DrumTriggerSource.INTERNAL,
    
    // Trigger States (Visual Feedback)
    val isBdActive: Boolean = false,
    val isSdActive: Boolean = false,
    val isHhActive: Boolean = false,
    val drumsBypass: Boolean = true
)

data class DrumPanelActions(
    // BD actions
    val setBdFrequency: (Float) -> Unit,
    val setBdTone: (Float) -> Unit,
    val setBdDecay: (Float) -> Unit,
    val setBdP4: (Float) -> Unit,
    val setBdTriggerSource: (DrumTriggerSource) -> Unit,
    val setBdPitchSource: (DrumTriggerSource) -> Unit,
    val startBdTrigger: () -> Unit,
    val stopBdTrigger: () -> Unit,
    
    // SD actions
    val setSdFrequency: (Float) -> Unit,
    val setSdTone: (Float) -> Unit,
    val setSdDecay: (Float) -> Unit,
    val setSdP4: (Float) -> Unit,
    val setSdTriggerSource: (DrumTriggerSource) -> Unit,
    val setSdPitchSource: (DrumTriggerSource) -> Unit,
    val startSdTrigger: () -> Unit,
    val stopSdTrigger: () -> Unit,
    
    // HH actions
    val setHhFrequency: (Float) -> Unit,
    val setHhTone: (Float) -> Unit,
    val setHhDecay: (Float) -> Unit,
    val setHhP4: (Float) -> Unit,
    val setHhTriggerSource: (DrumTriggerSource) -> Unit,
    val setHhPitchSource: (DrumTriggerSource) -> Unit,
    val startHhTrigger: () -> Unit,
    val stopHhTrigger: () -> Unit,
    
    // Global
    val setDrumsBypass: (Boolean) -> Unit
) {
    companion object {
        val EMPTY = DrumPanelActions(
            {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {}, {},
            {}, {}, {}, {}, {}, {}, {}, {}, {}
        )
    }
}

/** User intents for the Drum panel. */
private sealed interface DrumIntent {
    data class BdFrequency(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class BdTone(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class BdDecay(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class BdP4(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class BdTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class BdPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class BdTrigger(val active: Boolean, val fromSequencer: Boolean = false) : DrumIntent

    data class SdFrequency(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class SdTone(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class SdDecay(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class SdP4(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class SdTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class SdPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class SdTrigger(val active: Boolean, val fromSequencer: Boolean = false) : DrumIntent

    data class HhFrequency(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class HhTone(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class HhDecay(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class HhP4(val value: Float, val fromSequencer: Boolean = false) : DrumIntent
    data class HhTriggerSource(val source: DrumTriggerSource) : DrumIntent
    data class HhPitchSource(val source: DrumTriggerSource) : DrumIntent
    data class HhTrigger(val active: Boolean, val fromSequencer: Boolean = false) : DrumIntent

    data class Bypass(val active: Boolean) : DrumIntent
    data class Restore(val state: DrumUiState) : DrumIntent
}

typealias DrumFeature = SynthFeature<DrumUiState, DrumPanelActions>

@Inject
@ViewModelKey(DrumViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DrumViewModel(
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
    presetLoader: PresetLoader,
    dispatcherProvider: DispatcherProvider
) : ViewModel(), DrumFeature {

    override val actions = DrumPanelActions(
        // BD actions
        setBdFrequency = ::setBdFrequency,
        setBdTone = ::setBdTone,
        setBdDecay = ::setBdDecay,
        setBdP4 = ::setBdP4,
        setBdTriggerSource = ::setBdTriggerSource,
        setBdPitchSource = ::setBdPitchSource,
        startBdTrigger = ::startBdTrigger,
        stopBdTrigger = ::stopBdTrigger,

        // SD actions
        setSdFrequency = ::setSdFrequency,
        setSdTone = ::setSdTone,
        setSdDecay = ::setSdDecay,
        setSdP4 = ::setSdP4,
        setSdTriggerSource = ::setSdTriggerSource,
        setSdPitchSource = ::setSdPitchSource,
        startSdTrigger = ::startSdTrigger,
        stopSdTrigger = ::stopSdTrigger,

        // HH actions
        setHhFrequency = ::setHhFrequency,
        setHhTone = ::setHhTone,
        setHhDecay = ::setHhDecay,
        setHhP4 = ::setHhP4,
        setHhTriggerSource = ::setHhTriggerSource,
        setHhPitchSource = ::setHhPitchSource,
        startHhTrigger = ::startHhTrigger,
        stopHhTrigger = ::stopHhTrigger,

        // Global
        setDrumsBypass = ::setDrumsBypass
    )

    private val _userIntents = MutableSharedFlow<DrumIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Preset changes -> DrumIntent.Restore
    private val presetIntents = presetLoader.presetFlow.map { preset ->
        val sources = DrumTriggerSource.entries
        DrumIntent.Restore(
            DrumUiState(
                bdFrequency = preset.drumBdFrequency,
                bdTone = preset.drumBdTone,
                bdDecay = preset.drumBdDecay,
                bdP4 = preset.drumBdP4,
                bdP5 = preset.drumBdP5,
                bdTriggerSource = sources.getOrElse(preset.drumBdTriggerSource) { DrumTriggerSource.INTERNAL },
                bdPitchSource = sources.getOrElse(preset.drumBdPitchSource) { DrumTriggerSource.INTERNAL },
                
                sdFrequency = preset.drumSdFrequency,
                sdTone = preset.drumSdTone,
                sdDecay = preset.drumSdDecay,
                sdP4 = preset.drumSdP4,
                sdTriggerSource = sources.getOrElse(preset.drumSdTriggerSource) { DrumTriggerSource.INTERNAL },
                sdPitchSource = sources.getOrElse(preset.drumSdPitchSource) { DrumTriggerSource.INTERNAL },
                
                hhFrequency = preset.drumHhFrequency,
                hhTone = preset.drumHhTone,
                hhDecay = preset.drumHhDecay,
                hhP4 = preset.drumHhP4,
                hhTriggerSource = sources.getOrElse(preset.drumHhTriggerSource) { DrumTriggerSource.INTERNAL },
                hhPitchSource = sources.getOrElse(preset.drumHhPitchSource) { DrumTriggerSource.INTERNAL },
                
                drumsBypass = preset.drumsBypass
            )
        )
    }

    // Control changes -> DrumIntent
    private val controlIntents = synthController.onControlChange.mapNotNull { event ->
        val fromSequencer = event.origin == ControlEventOrigin.SEQUENCER
        val sources = DrumTriggerSource.entries
        fun getSource(value: Float) = sources.getOrElse((value * (sources.size - 1)).roundToInt().coerceIn(0, sources.size - 1)) { DrumTriggerSource.INTERNAL }

        when (event.controlId) {
            ControlIds.DRUM_BD_FREQ -> DrumIntent.BdFrequency(event.value, fromSequencer)
            ControlIds.DRUM_BD_TONE -> DrumIntent.BdTone(event.value, fromSequencer)
            ControlIds.DRUM_BD_DECAY -> DrumIntent.BdDecay(event.value, fromSequencer)
            ControlIds.DRUM_BD_AFM -> DrumIntent.BdP4(event.value, fromSequencer)
            ControlIds.DRUM_BD_TRIGGER_SOURCE -> DrumIntent.BdTriggerSource(getSource(event.value))
            ControlIds.DRUM_BD_PITCH_SOURCE -> DrumIntent.BdPitchSource(getSource(event.value))
            ControlIds.DRUM_BD_TRIGGER -> DrumIntent.BdTrigger(event.value >= 0.5f, fromSequencer)

            ControlIds.DRUM_SD_FREQ -> DrumIntent.SdFrequency(event.value, fromSequencer)
            ControlIds.DRUM_SD_TONE -> DrumIntent.SdTone(event.value, fromSequencer)
            ControlIds.DRUM_SD_DECAY -> DrumIntent.SdDecay(event.value, fromSequencer)
            ControlIds.DRUM_SD_SNAPPY -> DrumIntent.SdP4(event.value, fromSequencer)
            ControlIds.DRUM_SD_TRIGGER_SOURCE -> DrumIntent.SdTriggerSource(getSource(event.value))
            ControlIds.DRUM_SD_PITCH_SOURCE -> DrumIntent.SdPitchSource(getSource(event.value))
            ControlIds.DRUM_SD_TRIGGER -> DrumIntent.SdTrigger(event.value >= 0.5f, fromSequencer)

            ControlIds.DRUM_HH_FREQ -> DrumIntent.HhFrequency(event.value, fromSequencer)
            ControlIds.DRUM_HH_TONE -> DrumIntent.HhTone(event.value, fromSequencer)
            ControlIds.DRUM_HH_DECAY -> DrumIntent.HhDecay(event.value, fromSequencer)
            ControlIds.DRUM_HH_NOISY -> DrumIntent.HhP4(event.value, fromSequencer)
            ControlIds.DRUM_HH_TRIGGER_SOURCE -> DrumIntent.HhTriggerSource(getSource(event.value))
            ControlIds.DRUM_HH_PITCH_SOURCE -> DrumIntent.HhPitchSource(getSource(event.value))
            ControlIds.DRUM_HH_TRIGGER -> DrumIntent.HhTrigger(event.value >= 0.5f, fromSequencer)

            ControlIds.DRUMS_BYPASS -> DrumIntent.Bypass(event.value >= 0.5f)
            else -> null
        }
    }

    override val stateFlow: StateFlow<DrumUiState> =
        merge(_userIntents, presetIntents, controlIntents)
            .scan(DrumUiState()) { state, intent ->
                val newState = reduce(state, intent)
                applyToEngine(newState, intent)
                newState
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DrumUiState()
            )

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: DrumUiState, intent: DrumIntent): DrumUiState =
        when (intent) {
            is DrumIntent.BdFrequency -> state.copy(bdFrequency = intent.value)
            is DrumIntent.BdTone -> state.copy(bdTone = intent.value)
            is DrumIntent.BdDecay -> state.copy(bdDecay = intent.value)
            is DrumIntent.BdP4 -> state.copy(bdP4 = intent.value)
            is DrumIntent.BdTriggerSource -> state.copy(bdTriggerSource = intent.source)
            is DrumIntent.BdPitchSource -> state.copy(bdPitchSource = intent.source)
            is DrumIntent.BdTrigger -> state.copy(isBdActive = intent.active)

            is DrumIntent.SdFrequency -> state.copy(sdFrequency = intent.value)
            is DrumIntent.SdTone -> state.copy(sdTone = intent.value)
            is DrumIntent.SdDecay -> state.copy(sdDecay = intent.value)
            is DrumIntent.SdP4 -> state.copy(sdP4 = intent.value)
            is DrumIntent.SdTriggerSource -> state.copy(sdTriggerSource = intent.source)
            is DrumIntent.SdPitchSource -> state.copy(sdPitchSource = intent.source)
            is DrumIntent.SdTrigger -> state.copy(isSdActive = intent.active)

            is DrumIntent.HhFrequency -> state.copy(hhFrequency = intent.value)
            is DrumIntent.HhTone -> state.copy(hhTone = intent.value)
            is DrumIntent.HhDecay -> state.copy(hhDecay = intent.value)
            is DrumIntent.HhP4 -> state.copy(hhP4 = intent.value)
            is DrumIntent.HhTriggerSource -> state.copy(hhTriggerSource = intent.source)
            is DrumIntent.HhPitchSource -> state.copy(hhPitchSource = intent.source)
            is DrumIntent.HhTrigger -> state.copy(isHhActive = intent.active)

            is DrumIntent.Bypass -> state.copy(drumsBypass = intent.active)
            is DrumIntent.Restore -> intent.state
        }

    // ═══════════════════════════════════════════════════════════
    // ENGINE SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun applyToEngine(state: DrumUiState, intent: DrumIntent) {
        when (intent) {
            is DrumIntent.BdFrequency -> if (!intent.fromSequencer) updateBdParams(state)
            is DrumIntent.BdTone -> if (!intent.fromSequencer) updateBdParams(state)
            is DrumIntent.BdDecay -> if (!intent.fromSequencer) updateBdParams(state)
            is DrumIntent.BdP4 -> if (!intent.fromSequencer) updateBdParams(state)
            is DrumIntent.BdTriggerSource -> synthEngine.setDrumTriggerSource(0, intent.source.ordinal)
            is DrumIntent.BdPitchSource -> synthEngine.setDrumPitchSource(0, intent.source.ordinal)
            is DrumIntent.BdTrigger -> {
                if (intent.active) synthEngine.triggerDrum(0, 1.0f)
            }

            is DrumIntent.SdFrequency -> if (!intent.fromSequencer) updateSdParams(state)
            is DrumIntent.SdTone -> if (!intent.fromSequencer) updateSdParams(state)
            is DrumIntent.SdDecay -> if (!intent.fromSequencer) updateSdParams(state)
            is DrumIntent.SdP4 -> if (!intent.fromSequencer) updateSdParams(state)
            is DrumIntent.SdTriggerSource -> synthEngine.setDrumTriggerSource(1, intent.source.ordinal)
            is DrumIntent.SdPitchSource -> synthEngine.setDrumPitchSource(1, intent.source.ordinal)
            is DrumIntent.SdTrigger -> {
                if (intent.active) synthEngine.triggerDrum(1, 1.0f)
            }

            is DrumIntent.HhFrequency -> if (!intent.fromSequencer) updateHhParams(state)
            is DrumIntent.HhTone -> if (!intent.fromSequencer) updateHhParams(state)
            is DrumIntent.HhDecay -> if (!intent.fromSequencer) updateHhParams(state)
            is DrumIntent.HhP4 -> if (!intent.fromSequencer) updateHhParams(state)
            is DrumIntent.HhTriggerSource -> synthEngine.setDrumTriggerSource(2, intent.source.ordinal)
            is DrumIntent.HhPitchSource -> synthEngine.setDrumPitchSource(2, intent.source.ordinal)
            is DrumIntent.HhTrigger -> {
                if (intent.active) synthEngine.triggerDrum(2, 1.0f)
            }

            is DrumIntent.Bypass -> synthEngine.setDrumsBypass(intent.active)
            is DrumIntent.Restore -> applyFullState(intent.state)
        }
    }

    private fun applyFullState(state: DrumUiState) {
        updateBdParams(state)
        updateSdParams(state)
        updateHhParams(state)
        synthEngine.setDrumTriggerSource(0, state.bdTriggerSource.ordinal)
        synthEngine.setDrumPitchSource(0, state.bdPitchSource.ordinal)
        synthEngine.setDrumTriggerSource(1, state.sdTriggerSource.ordinal)
        synthEngine.setDrumPitchSource(1, state.sdPitchSource.ordinal)
        synthEngine.setDrumTriggerSource(2, state.hhTriggerSource.ordinal)
        synthEngine.setDrumPitchSource(2, state.hhPitchSource.ordinal)
        synthEngine.setDrumsBypass(state.drumsBypass)
    }

    private fun updateBdParams(s: DrumUiState) {
        synthEngine.setDrumTone(0, s.bdFrequency, s.bdTone, s.bdDecay, s.bdP4, s.bdP5)
    }

    private fun updateSdParams(s: DrumUiState) {
        synthEngine.setDrumTone(1, s.sdFrequency, s.sdTone, s.sdDecay, s.sdP4, 0.5f)
    }

    private fun updateHhParams(s: DrumUiState) {
        synthEngine.setDrumTone(2, s.hhFrequency, s.hhTone, s.hhDecay, s.hhP4, 0.5f)
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    fun setBdFrequency(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_BD_FREQ, value, ControlEventOrigin.UI)
    }

    fun setBdTone(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_BD_TONE, value, ControlEventOrigin.UI)
    }

    fun setBdDecay(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_BD_DECAY, value, ControlEventOrigin.UI)
    }

    fun setBdP4(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_BD_AFM, value, ControlEventOrigin.UI)
    }

    fun setBdTriggerSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_BD_TRIGGER_SOURCE, v, ControlEventOrigin.UI)
    }

    fun setBdPitchSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_BD_PITCH_SOURCE, v, ControlEventOrigin.UI)
    }

    fun startBdTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_BD_TRIGGER, 1f, ControlEventOrigin.UI)
    }

    fun stopBdTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_BD_TRIGGER, 0f, ControlEventOrigin.UI)
    }

    fun setSdFrequency(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_SD_FREQ, value, ControlEventOrigin.UI)
    }

    fun setSdTone(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_SD_TONE, value, ControlEventOrigin.UI)
    }

    fun setSdDecay(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_SD_DECAY, value, ControlEventOrigin.UI)
    }

    fun setSdP4(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_SD_SNAPPY, value, ControlEventOrigin.UI)
    }

    fun setSdTriggerSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_SD_TRIGGER_SOURCE, v, ControlEventOrigin.UI)
    }

    fun setSdPitchSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_SD_PITCH_SOURCE, v, ControlEventOrigin.UI)
    }

    fun startSdTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_SD_TRIGGER, 1f, ControlEventOrigin.UI)
    }

    fun stopSdTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_SD_TRIGGER, 0f, ControlEventOrigin.UI)
    }

    fun setHhFrequency(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_HH_FREQ, value, ControlEventOrigin.UI)
    }

    fun setHhTone(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_HH_TONE, value, ControlEventOrigin.UI)
    }

    fun setHhDecay(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_HH_DECAY, value, ControlEventOrigin.UI)
    }

    fun setHhP4(value: Float) {
        synthController.emitControlChange(ControlIds.DRUM_HH_NOISY, value, ControlEventOrigin.UI)
    }

    fun setHhTriggerSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_HH_TRIGGER_SOURCE, v, ControlEventOrigin.UI)
    }

    fun setHhPitchSource(source: DrumTriggerSource) {
        val sources = DrumTriggerSource.entries
        val v = if (sources.isNotEmpty()) source.ordinal.toFloat() / (sources.size - 1) else 0f
        synthController.emitControlChange(ControlIds.DRUM_HH_PITCH_SOURCE, v, ControlEventOrigin.UI)
    }

    fun startHhTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_HH_TRIGGER, 1f, ControlEventOrigin.UI)
    }

    fun stopHhTrigger() {
        synthController.emitControlChange(ControlIds.DRUM_HH_TRIGGER, 0f, ControlEventOrigin.UI)
    }

    fun setDrumsBypass(bypass: Boolean) {
        synthController.emitControlChange(ControlIds.DRUMS_BYPASS, if (bypass) 1f else 0f, ControlEventOrigin.UI)
    }

    companion object {
        fun previewFeature(state: DrumUiState = DrumUiState()): DrumFeature =
            object : DrumFeature {
                override val stateFlow: StateFlow<DrumUiState> = MutableStateFlow(state)
                override val actions: DrumPanelActions = DrumPanelActions.EMPTY
            }

        @Composable
        fun feature(): DrumFeature =
            synthViewModel<DrumViewModel, DrumFeature>()
    }
}
