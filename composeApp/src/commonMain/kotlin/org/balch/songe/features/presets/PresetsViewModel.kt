package org.balch.songe.features.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.core.presets.DronePreset
import org.balch.songe.core.presets.DronePresetRepository
import org.balch.songe.core.presets.PresetLoader
import org.balch.songe.util.Logger

/** UI state for the Presets panel. */
data class PresetUiState(
    val presets: List<DronePreset> = emptyList(),
    val selectedPreset: DronePreset? = null,
    val isLoading: Boolean = false
)

/** User intents for the Presets panel. */
private sealed interface PresetIntent {
    data class SetPresets(val presets: List<DronePreset>) : PresetIntent
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
@ContributesIntoMap(AppScope::class)
class PresetsViewModel(
    private val repository: DronePresetRepository,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val intents =
        MutableSharedFlow<PresetIntent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uiState: StateFlow<PresetUiState> =
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
                state.copy(presets = intent.presets)

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
            val presets = repository.list()
            intents.tryEmit(PresetIntent.SetPresets(presets))
            intents.tryEmit(PresetIntent.SetLoading(false))
            Logger.info { "Loaded ${presets.size} presets" }
        }
    }

    fun selectPreset(preset: DronePreset?) {
        intents.tryEmit(PresetIntent.Select(preset))
    }

    fun applyPreset(preset: DronePreset) {
        selectPreset(preset)
        presetLoader.applyPreset(preset)
        Logger.info { "Applied preset: ${preset.name}" }
    }

    fun saveNewPreset(name: String) {
        val preset = presetLoader.currentStateAsPreset(name)
        viewModelScope.launch(dispatcherProvider.io) {
            repository.save(preset)
            val updatedList = repository.list()
            intents.tryEmit(PresetIntent.SetPresets(updatedList))
            intents.tryEmit(PresetIntent.Select(updatedList.find { it.name == name }))
            Logger.info { "Saved new preset: $name" }
        }
    }

    fun overridePreset() {
        val current = uiState.value.selectedPreset ?: return
        val preset = presetLoader.currentStateAsPreset(current.name).copy(createdAt = current.createdAt)
        viewModelScope.launch(dispatcherProvider.io) {
            repository.save(preset)
            val updatedList = repository.list()
            intents.tryEmit(PresetIntent.SetPresets(updatedList))
            intents.tryEmit(PresetIntent.Select(updatedList.find { it.name == current.name }))
            Logger.info { "Overrode preset: ${current.name}" }
        }
    }

    fun deletePreset() {
        val current = uiState.value.selectedPreset ?: return
        viewModelScope.launch(dispatcherProvider.io) {
            repository.delete(current.name)
            val updatedList = repository.list()
            intents.tryEmit(PresetIntent.SetPresets(updatedList))
            intents.tryEmit(PresetIntent.Select(null))
            Logger.info { "Deleted preset: ${current.name}" }
        }
    }
}
