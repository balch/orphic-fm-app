package org.balch.orpheus.features.presets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.presets.PresetLoader
import org.balch.orpheus.core.presets.PresetsRepository
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.core.FeatureCoroutineScope
import org.balch.orpheus.core.synthFeature

/** UI state for the Presets panel. */
@Immutable
sealed interface PresetUiState {
    data object Loading : PresetUiState
    data class Loaded(
        val presets: List<SynthPreset> = emptyList(),
        val selectedPreset: SynthPreset? = null,
        val factoryPresetNames: Set<String> = emptySet() // Track which presets are factory (read-only)
    ): PresetUiState
}

@Immutable
data class PresetPanelActions(
    val selectPreset: (SynthPreset) -> Unit,
    val applyPreset: (SynthPreset) -> Unit,
    val saveNewPreset: (String) -> Unit,
    val overridePreset: (SynthPreset) -> Unit,
    val deletePreset: (SynthPreset) -> Unit,
    val setDialogActive: (Boolean) -> Unit = {} // Called when naming dialog opens/closes
) {
    companion object {
        val EMPTY = PresetPanelActions(
            selectPreset = {},
            applyPreset = {},
            saveNewPreset = {},
            overridePreset = {},
            deletePreset = {},
            setDialogActive = {}
        )
    }
}

private sealed interface PresetIntent {
    data class SelectPreset(val preset: SynthPreset?) : PresetIntent
    data class ApplyPreset(val preset: SynthPreset) : PresetIntent
    data class SaveNewPreset(val name: String) : PresetIntent
    data class OverridePreset(val preset: SynthPreset) : PresetIntent
    data class DeletePreset(val preset: SynthPreset) : PresetIntent
    data class RefreshPresets(val presets: List<SynthPreset>, val selectName: String? = null) : PresetIntent
}

interface PresetsFeature : SynthFeature<PresetUiState, PresetPanelActions> {
    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly

    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 * ViewModel for the Presets panel.
 *
 * Uses MVI pattern: intents flow through a reducer (scan) to produce state.
 */
@Inject
@ClassKey(PresetsViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class PresetsViewModel(
    private val presetsRepository: PresetsRepository,
    private val presetLoader: PresetLoader,
    private val dispatcherProvider: DispatcherProvider,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val scope: FeatureCoroutineScope
) : PresetsFeature {

    override val actions = PresetPanelActions(
        selectPreset = ::selectPreset,
        applyPreset = ::applyPreset,
        saveNewPreset = ::saveNewPreset,
        overridePreset = ::overridePreset,
        deletePreset = ::deletePreset,
        setDialogActive = { /* noop by default */ }
    )

    private val log = logging("PresetsViewModel")

    private val _userIntents = MutableSharedFlow<PresetIntent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch(dispatcherProvider.io) {
            val initial = loadPresets()
            // Session restore: Apply last selected preset once when the ViewModel is first created.
            // This avoids re-applying presets and interrupting audio when swiping panels in compact mode.
            initial.selectedPreset?.let { presetLoader.applyPreset(it) }
            _userIntents.emit(PresetIntent.RefreshPresets(initial.presets, initial.selectedPreset?.name))
        }
    }

    override val stateFlow: StateFlow<PresetUiState> =
        _userIntents
            .onEach { handleSideEffects(it) }
            .scan(PresetUiState.Loading as PresetUiState) { state, intent ->
                if (state is PresetUiState.Loading && intent is PresetIntent.RefreshPresets) {
                    PresetUiState.Loaded(
                        presets = intent.presets,
                        selectedPreset = intent.presets.find { it.name == intent.selectName },
                        factoryPresetNames = presetsRepository.getFactoryPresetNames()
                    )
                } else if (state is PresetUiState.Loaded) {
                    reduce(state, intent)
                } else {
                    state
                }
            }
            .flowOn(dispatcherProvider.io)
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
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
    fun isFactoryPreset(preset: SynthPreset): Boolean = presetsRepository.isFactoryPreset(preset.name)

    fun selectPreset(preset: SynthPreset?) {
        _userIntents.tryEmit(PresetIntent.SelectPreset(preset))
    }

    fun applyPreset(preset: SynthPreset) {
        _userIntents.tryEmit(PresetIntent.ApplyPreset(preset))
    }

    fun saveNewPreset(name: String) {
        _userIntents.tryEmit(PresetIntent.SaveNewPreset(name))
    }

    fun overridePreset(preset: SynthPreset) {
        _userIntents.tryEmit(PresetIntent.OverridePreset(preset))
    }

    fun deletePreset(preset: SynthPreset) {
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
                scope.launch(dispatcherProvider.io) {
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
        fun previewFeature(state: PresetUiState = PresetUiState.Loading): PresetsFeature =
            object : PresetsFeature {
                override val stateFlow: StateFlow<PresetUiState> = MutableStateFlow(state)
                override val actions: PresetPanelActions = PresetPanelActions.EMPTY
            }

        @Composable
        fun feature(): PresetsFeature =
            synthFeature<PresetsViewModel, PresetsFeature>()
    }
}
