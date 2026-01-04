package org.balch.orpheus.features.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.PresetsRepository
import org.balch.orpheus.ui.utils.PanelViewModel
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper

/** UI state for the Presets panel. */
sealed interface PresetUiState {
    data object Loading : PresetUiState
    data class Loaded(
        val presets: List<DronePreset> = emptyList(),
        val selectedPreset: DronePreset? = null,
        val factoryPresetNames: Set<String> = emptySet() // Track which presets are factory (read-only)
    ): PresetUiState
}

data class PresetPanelActions(
    val onPresetSelect: (DronePreset) -> Unit,
    val onSelect: (DronePreset) -> Unit,
    val onNew: (String) -> Unit,
    val onOverride: (DronePreset) -> Unit,
    val onDelete: (DronePreset) -> Unit,
    val onApply: (DronePreset) -> Unit,
    val onDialogActiveChange: (Boolean) -> Unit = {} // Called when naming dialog opens/closes
) {
    companion object {
        val EMPTY = PresetPanelActions(
            onPresetSelect = {},
            onSelect = {},
            onNew = {},
            onOverride = {},
            onDelete = {},
            onApply = {}
        )
    }
}

private sealed interface PresetIntent {
    data class SelectPreset(val preset: DronePreset?) : PresetIntent
    data class ApplyPreset(val preset: DronePreset) : PresetIntent
    data class SaveNewPreset(val name: String) : PresetIntent
    data class OverridePreset(val preset: DronePreset) : PresetIntent
    data class DeletePreset(val preset: DronePreset) : PresetIntent
    data class RefreshPresets(val presets: List<DronePreset>, val selectName: String? = null) : PresetIntent
}

/**
 * ViewModel for the Presets panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ViewModelKey(PresetsViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class PresetsViewModel(
    private val presetsRepository: PresetsRepository,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
    private val appPreferencesRepository: AppPreferencesRepository
) : ViewModel(), PanelViewModel<PresetUiState, PresetPanelActions> {

    override val panelActions = PresetPanelActions(
        onPresetSelect = ::selectPreset,
        onSelect = ::applyPreset,
        onNew = ::saveNewPreset,
        onOverride = ::overridePreset,
        onDelete = ::deletePreset,
        onApply = ::applyPreset
    )

    private val log = logging("PresetsViewModel")

    private val _userIntents = MutableSharedFlow<PresetIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val uiState: StateFlow<PresetUiState> =
        flow<PresetUiState> {
            val initial = loadPresets()
            initial.selectedPreset?.let { presetLoader.applyPreset(it) }
            emit(initial)
            
            emitAll(
                _userIntents
                    .onEach { handleSideEffects(it) }
                    .scan(initial as PresetUiState) { state, intent -> reduce(state, intent) }
            )
        }
        .flowOn(dispatcherProvider.io)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PresetUiState.Loading
        )


    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    private suspend fun loadPresets(): PresetUiState.Loaded {
        val allPresets = presetsRepository.getAll()
        log.debug { "Loaded ${allPresets.size} presets" }

        // Load last selected preset from preferences, fallback to Default
        val prefs = appPreferencesRepository.load()
        val lastPresetName = prefs.lastPresetName ?: "Default"
        val presetToApply = allPresets.find { it.name == lastPresetName }
            ?: presetsRepository.getDefault()
        return PresetUiState.Loaded(allPresets, presetToApply, presetsRepository.getFactoryPresetNames())
    }

    /** Check if a preset is a factory preset (read-only) */
    fun isFactoryPreset(preset: DronePreset): Boolean = presetsRepository.isFactoryPreset(preset.name)

    fun selectPreset(preset: DronePreset?) {
        _userIntents.tryEmit(PresetIntent.SelectPreset(preset))
    }

    fun applyPreset(preset: DronePreset) {
        _userIntents.tryEmit(PresetIntent.ApplyPreset(preset))
    }

    fun saveNewPreset(name: String) {
        _userIntents.tryEmit(PresetIntent.SaveNewPreset(name))
    }

    fun overridePreset(preset: DronePreset) {
        _userIntents.tryEmit(PresetIntent.OverridePreset(preset))
    }

    fun deletePreset(preset: DronePreset) {
        _userIntents.tryEmit(PresetIntent.DeletePreset(preset))
    }
    
    // ═══════════════════════════════════════════════════════════
    // REDUCER & SIDE EFFECTS
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: PresetUiState, intent: PresetIntent): PresetUiState {
        if (state !is PresetUiState.Loaded) return state
        
        return when (intent) {
            is PresetIntent.SelectPreset -> state.copy(selectedPreset = intent.preset)
            is PresetIntent.ApplyPreset -> state.copy(selectedPreset = intent.preset)
            is PresetIntent.RefreshPresets -> {
                val selected = if (intent.selectName != null) {
                    intent.presets.find { it.name == intent.selectName } ?: state.selectedPreset
                } else {
                    state.selectedPreset
                }
                state.copy(presets = intent.presets, selectedPreset = selected)
            }
            // These intents are side-effect initiators, no immediate state change needed
            is PresetIntent.SaveNewPreset -> state 
            is PresetIntent.OverridePreset -> state
            is PresetIntent.DeletePreset -> state
        }
    }

    private suspend fun handleSideEffects(intent: PresetIntent) {
        when (intent) {
            is PresetIntent.ApplyPreset -> {
                presetLoader.applyPreset(intent.preset)
                log.info { "Applied preset: ${intent.preset.name}" }
                viewModelScope.launch(dispatcherProvider.io) {
                    val prefs = appPreferencesRepository.load().copy(lastPresetName = intent.preset.name)
                    appPreferencesRepository.save(prefs)
                }
            }
            is PresetIntent.SaveNewPreset -> {
                if (presetsRepository.isFactoryPreset(intent.name)) {
                    log.warn { "Cannot overwrite factory preset: ${intent.name}" }
                    return
                }
                val preset = presetLoader.currentStateAsPreset(intent.name)
                presetsRepository.save(preset)
                log.info { "Saved new preset: ${intent.name}" }
                refreshPresets(selectName = intent.name)
            }
            is PresetIntent.OverridePreset -> {
                if (presetsRepository.isFactoryPreset(intent.preset.name)) {
                    log.warn { "Cannot overwrite factory preset: ${intent.preset.name}" }
                    return
                }
                val newPreset = presetLoader.currentStateAsPreset(intent.preset.name).copy(createdAt = intent.preset.createdAt)
                presetsRepository.save(newPreset)
                log.info { "Overrode preset: ${intent.preset.name}" }
                refreshPresets(selectName = intent.preset.name)
            }
            is PresetIntent.DeletePreset -> {
                if (presetsRepository.isFactoryPreset(intent.preset.name)) {
                    log.warn { "Cannot delete factory preset: ${intent.preset.name}" }
                    return
                }
                presetsRepository.delete(intent.preset.name)
                log.info { "Deleted preset: ${intent.preset.name}" }
                val allPresets = presetsRepository.getAll() // Reload
                // We emit RefreshPresets which will handle the state update (including clearing selection if needed)
                // Actually RefreshPresets logic tries to keep selection.
                // If deleted, we should probably select null or default.
                _userIntents.emit(PresetIntent.RefreshPresets(allPresets, selectName = null))
            }
            else -> {}
        }
    }
    
    private suspend fun refreshPresets(selectName: String? = null) {
        val all = presetsRepository.getAll()
        _userIntents.emit(PresetIntent.RefreshPresets(all, selectName))
    }

    companion object {
        val PREVIEW = ViewModelStateActionMapper(
            state = PresetUiState.Loading as PresetUiState,
            actions = PresetPanelActions.EMPTY,
        )
    }
}
