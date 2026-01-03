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
    val onOverride: () -> Unit,
    val onDelete: () -> Unit,
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

    private val _userIntent = MutableSharedFlow<PresetUiState.Loaded>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val uiState: StateFlow<PresetUiState> =
        flow<PresetUiState> {
            emit(loadPresets())
            emitAll(_userIntent)
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
        log.info { "Loaded ${allPresets.size} presets" }

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
        (uiState.value as? PresetUiState.Loaded)?.let {
            _userIntent.tryEmit(it.copy(selectedPreset = preset))
        }
    }

    fun applyPreset(preset: DronePreset) {
        selectPreset(preset)
        presetLoader.applyPreset(preset)
        log.info { "Applied preset: ${preset.name}" }
        
        // Save last selected preset to preferences
        viewModelScope.launch(dispatcherProvider.io) {
            val prefs = appPreferencesRepository.load().copy(lastPresetName = preset.name)
            appPreferencesRepository.save(prefs)
        }
    }

    fun saveNewPreset(name: String) {
        // Prevent overwriting factory presets
        val currentState = (uiState.value as? PresetUiState.Loaded) ?: return

        if (presetsRepository.isFactoryPreset(name)) {
            log.warn { "Cannot overwrite factory preset: $name" }
            return
        }
        val preset = presetLoader.currentStateAsPreset(name)
        viewModelScope.launch(dispatcherProvider.io) {
            presetsRepository.save(preset)
            loadPresetsAfterChange(currentState, name)
            log.info { "Saved new preset: $name" }
        }
    }

    fun overridePreset() {
        val currentState = (uiState.value as? PresetUiState.Loaded)
        val current = currentState?.selectedPreset ?: return
        // Prevent overwriting factory presets
        if (presetsRepository.isFactoryPreset(current.name)) {
            log.warn { "Cannot overwrite factory preset: ${current.name}" }
            return
        }
        val preset = presetLoader.currentStateAsPreset(current.name).copy(createdAt = current.createdAt)
        viewModelScope.launch(dispatcherProvider.io) {
            presetsRepository.save(preset)
            loadPresetsAfterChange(currentState, current.name)
            log.info { "Overrode preset: ${current.name}" }
        }
    }

    fun deletePreset() {
        val currentState = (uiState.value as? PresetUiState.Loaded)
        val current = currentState?.selectedPreset ?: return
        // Prevent deleting factory presets
        if (presetsRepository.isFactoryPreset(current.name)) {
            log.warn { "Cannot delete factory preset: ${current.name}" }
            return
        }
        viewModelScope.launch(dispatcherProvider.io) {
            presetsRepository.delete(current.name)
            val allPresets = presetsRepository.getAll()
            _userIntent.emit(currentState.copy(presets = allPresets, selectedPreset = null))
            log.info { "Deleted preset: ${current.name}" }
        }
    }

    /** Helper to reload presets after a change and select the given preset name */
    private suspend fun loadPresetsAfterChange(
        loadedState: PresetUiState.Loaded,
        selectName: String
    ) {
        val allPresets = presetsRepository.getAll()
        _userIntent.emit(
            loadedState.copy(
                presets = allPresets,
                selectedPreset = allPresets.find { it.name == selectName }
            )
        )
    }

    companion object {
        val PREVIEW = ViewModelStateActionMapper(
            state = PresetUiState.Loading as PresetUiState,
            actions = PresetPanelActions.EMPTY,
        )
    }
}
