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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.DronePreset
import org.balch.orpheus.core.presets.DronePresetRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.ui.utils.PanelViewModel

/** UI state for the Presets panel. */
data class PresetUiState(
    val presets: List<DronePreset> = emptyList(),
    val selectedPreset: DronePreset? = null,
    val isLoading: Boolean = false,
    val factoryPresetNames: Set<String> = emptySet() // Track which presets are factory (read-only)
)

data class PresetPanelActions(
    val onPresetSelect: (DronePreset) -> Unit,
    val onSelect: (DronePreset) -> Unit,
    val onNew: (String) -> Unit,
    val onOverride: () -> Unit,
    val onDelete: () -> Unit,
    val onApply: (DronePreset) -> Unit,
    val onDialogActiveChange: (Boolean) -> Unit = {} // Called when naming dialog opens/closes
)

/** User intents for the Presets panel. */
private sealed interface PresetIntent {
    data class SetPresets(val presets: List<DronePreset>, val factoryNames: Set<String>) : PresetIntent
    data class Select(val preset: DronePreset?) : PresetIntent
    data class SetLoading(val loading: Boolean) : PresetIntent
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
    private val repository: DronePresetRepository,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
    private val appPreferencesRepository: AppPreferencesRepository,
    factoryPatches: Set<SynthPatch>
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

    // Convert factory patches to DronePresets, sorted by name
    private val factoryPresets: List<DronePreset> = factoryPatches
        .map { it.preset }
        .sortedBy { it.name }

    private val factoryPresetNames: Set<String> = factoryPatches.map { it.name }.toSet()

    private val intents =
        MutableSharedFlow<PresetIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    override val uiState: StateFlow<PresetUiState> =
        intents
            .scan(PresetUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = PresetUiState()
            )

    init {
        loadPresets()
    }

    // ═══════════════════════════════════════════════════════════
    // REDUCER
    // ═══════════════════════════════════════════════════════════

    private fun reduce(state: PresetUiState, intent: PresetIntent): PresetUiState =
        when (intent) {
            is PresetIntent.SetPresets ->
                state.copy(presets = intent.presets, factoryPresetNames = intent.factoryNames)

            is PresetIntent.Select ->
                state.copy(selectedPreset = intent.preset)

            is PresetIntent.SetLoading ->
                state.copy(isLoading = intent.loading)
        }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC INTENT METHODS
    // ═══════════════════════════════════════════════════════════

    private fun loadPresets() {
        viewModelScope.launch(dispatcherProvider.io) {
            intents.tryEmit(PresetIntent.SetLoading(true))
            val userPresets = repository.list()
            // Combine factory presets (first) + user presets (sorted by creation date)
            val allPresets = factoryPresets + userPresets
            intents.tryEmit(PresetIntent.SetPresets(allPresets, factoryPresetNames))
            intents.tryEmit(PresetIntent.SetLoading(false))
            log.info { "Loaded ${factoryPresets.size} factory + ${userPresets.size} user presets" }
            
            // Load last selected preset from preferences, fallback to Default
            val prefs = appPreferencesRepository.load()
            val lastPresetName = prefs.lastPresetName ?: "Default"
            val presetToApply = allPresets.find { it.name == lastPresetName }
                ?: allPresets.find { it.name == "Default" }
            
            if (presetToApply != null) {
                intents.tryEmit(PresetIntent.Select(presetToApply))
                presetLoader.applyPreset(presetToApply)
                log.info { "Applied preset on startup: ${presetToApply.name}" }
            }
        }
    }

    /** Check if a preset is a factory preset (read-only) */
    fun isFactoryPreset(preset: DronePreset): Boolean = preset.name in factoryPresetNames

    fun selectPreset(preset: DronePreset?) {
        intents.tryEmit(PresetIntent.Select(preset))
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
        if (name in factoryPresetNames) {
            log.warn { "Cannot overwrite factory preset: $name" }
            return
        }
        val preset = presetLoader.currentStateAsPreset(name)
        viewModelScope.launch(dispatcherProvider.io) {
            repository.save(preset)
            loadPresetsAfterChange(name)
            log.info { "Saved new preset: $name" }
        }
    }

    fun overridePreset() {
        val current = uiState.value.selectedPreset ?: return
        // Prevent overwriting factory presets
        if (current.name in factoryPresetNames) {
            log.warn { "Cannot overwrite factory preset: ${current.name}" }
            return
        }
        val preset = presetLoader.currentStateAsPreset(current.name).copy(createdAt = current.createdAt)
        viewModelScope.launch(dispatcherProvider.io) {
            repository.save(preset)
            loadPresetsAfterChange(current.name)
            log.info { "Overrode preset: ${current.name}" }
        }
    }

    fun deletePreset() {
        val current = uiState.value.selectedPreset ?: return
        // Prevent deleting factory presets
        if (current.name in factoryPresetNames) {
            log.warn { "Cannot delete factory preset: ${current.name}" }
            return
        }
        viewModelScope.launch(dispatcherProvider.io) {
            repository.delete(current.name)
            val userPresets = repository.list()
            val allPresets = factoryPresets + userPresets
            intents.tryEmit(PresetIntent.SetPresets(allPresets, factoryPresetNames))
            intents.tryEmit(PresetIntent.Select(null))
            log.info { "Deleted preset: ${current.name}" }
        }
    }

    /** Helper to reload presets after a change and select the given preset name */
    private suspend fun loadPresetsAfterChange(selectName: String) {
        val userPresets = repository.list()
        val allPresets = factoryPresets + userPresets
        intents.tryEmit(PresetIntent.SetPresets(allPresets, factoryPresetNames))
        intents.tryEmit(PresetIntent.Select(allPresets.find { it.name == selectName }))
    }
}
