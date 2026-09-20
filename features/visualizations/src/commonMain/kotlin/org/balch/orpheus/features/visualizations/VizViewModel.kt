package org.balch.orpheus.features.visualizations

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.plugin.symbols.VizSymbol
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.features.visualizations.viz.OffViz
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.DynamicVisualization
import org.balch.orpheus.ui.viz.Visualization


/**
 * UI State for the VIZ panel.
 */
data class VizUiState(
    val selectedViz: Visualization,
    val visualizations: List<Visualization>,
    val showKnobs: Boolean,
    val knob1Value: Float = 0.5f,
    val knob2Value: Float = 0.5f,
    val liquidEffects: VisualizationLiquidEffects = selectedViz.liquidEffects,
    val signalVizEnabled: Boolean = false,
    val isRandomVizMode: Boolean = false,
    /** True while [visualizations] and the selection are locked to a song-exclusive viz. */
    val isVizLocked: Boolean = false,
)

data class VizPanelActions(
    val onSelectViz: (Visualization) -> Unit,
    val onKnob1Change: (Float) -> Unit,
    val onKnob2Change: (Float) -> Unit,
    val onToggleSignalViz: (Boolean) -> Unit,
    val onSetRandomMode: (Boolean) -> Unit = {},
    val onSelectRandomViz: () -> Unit = {},
) {
    companion object {
        val EMPTY = VizPanelActions(
            onSelectViz = {},
            onKnob1Change = {},
            onKnob2Change = {},
            onToggleSignalViz = {},
        )
    }
}

interface VizFeature : SynthFeature<VizUiState, VizPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 * ViewModel for managing visualizations.
 * Injects all available Visualization implementations.
 *
 * **Threading contract**: [Visualization.onActivate], [Visualization.onDeactivate], and
 * [Visualization.setKnob1]/[Visualization.setKnob2] must all be called on the main thread.
 * Visualization internal state is owned by the main-thread Compose frame loop (`withFrameNanos`),
 * so any mutation from a background thread would race with the frame loop and can cause
 * `ConcurrentModificationException`. Preference persistence is the only operation intentionally
 * dispatched to [DispatcherProvider.default].
 */
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(VizFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<VizFeature>())
class VizViewModel(
    visualizations: Set<Visualization>,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val synthController: SynthController,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
    private val metadataProducer: MetadataProducer,
) : VizFeature {

    override val actions = VizPanelActions(
        onSelectViz = { selectVisualization(it) },
        onKnob1Change = ::onKnob1Change,
        onKnob2Change = ::onKnob2Change,
        onToggleSignalViz = ::onToggleSignalViz,
        onSetRandomMode = ::setRandomMode,
        onSelectRandomViz = ::selectRandomVisualization,
    )

    // Sorted list: Off first, then alphabetical by name
    private val sortedVisualizations = visualizations.sortedWith(
        compareBy<Visualization> { it.id != "off" }.thenBy { it.name }
    )

    // Current selection
    private val _currentViz = MutableStateFlow(sortedVisualizations.first())

    // The selected song (MetadataProducer.songFlow), tracked outside uiState so
    // availableVisualizations() can be recomputed synchronously wherever it's needed.
    private var currentSongTitle = ""

    // Selection to restore once a song-exclusive lock releases. Only ever holds a
    // non-exclusive viz; it is captured right before the exclusive viz becomes current.
    private var vizBeforeLock: Visualization? = null

    private fun availableVisualizations(): List<Visualization> =
        sortedVisualizations.filter { it.exclusiveToSong == null || it.exclusiveToSong == currentSongTitle }

    private val _uiState = MutableStateFlow(
        VizUiState(
            selectedViz = sortedVisualizations.first(),
            visualizations = availableVisualizations(),
            showKnobs = sortedVisualizations.first().id != "off",
            liquidEffects = sortedVisualizations.first().liquidEffects
        )
    )
    override val stateFlow: StateFlow<VizUiState> = _uiState.asStateFlow()

    init {
        // Activate initial visualization if it's not off (likely is off initially).
        // Must run on main: Visualization internal state is owned by the main-thread frame loop.
        if (_currentViz.value.id != "off") {
            scope.launch(dispatcherProvider.main) {
                _currentViz.value.onActivate()
            }
        }

        // songFlow (not titleFlow): the selected vibe, immune to any title overlay (e.g.
        // Orpheus's AI/Evo modes), so those can't fight the lock or unlock it by accident.
        scope.launch(dispatcherProvider.main) {
            metadataProducer.songFlow.collect { title -> onSongTitleChanged(title) }
        }

        scope.launch(dispatcherProvider.default) {
            val prefs = appPreferencesRepository.load()
            _uiState.update {
                it.copy(
                    signalVizEnabled = prefs.signalVizEnabled,
                    isRandomVizMode = prefs.randomVizMode,
                )
            }
            if (prefs.randomVizMode) {
                selectRandomVisualization()
            } else {
                prefs.lastVizId?.let { id ->
                    sortedVisualizations.find { it.id == id }?.let { viz ->
                        // A stored id can name a song-exclusive viz whose song isn't playing
                        // (e.g. it was cleared while the app was closed); fall through to the
                        // default selection instead of showing it unlocked.
                        if (viz.exclusiveToSong == null) {
                            // Hops to main because this races the songFlow collector (also
                            // launched on main): if the lock engages first, vizBeforeLock is
                            // a plain var that main also reads/writes on release, so the
                            // decision of whether to select now or just remember this viz for
                            // later has to happen on main too, not interleaved from default.
                            scope.launch(dispatcherProvider.main) {
                                restoreStoredViz(viz)
                            }
                        }
                    }
                }
            }
        }
        
        // Subscribe to viz knob control flows (bidirectional with MIDI)
        // Collect on Main to serialize updates and prevent out-of-order processing
        scope.launch(dispatcherProvider.main) {
            synthController.controlFlow(VizSymbol.KNOB_1.controlId).collect { value ->
                onKnob1Change(value.asFloat())
            }
        }
        scope.launch(dispatcherProvider.main) {
            synthController.controlFlow(VizSymbol.KNOB_2.controlId).collect { value ->
                onKnob2Change(value.asFloat())
            }
        }
    }

    /**
     * Select a new visualization by instance.
     */
    private var dynamicEffectsJob: Job? = null

    /**
     * Select a new visualization by instance.
     * When [save] is true (explicit user pick), random mode is disabled.
     * No-op while [VizUiState.isVizLocked]: the lock owns the selection until it releases.
     */
    fun selectVisualization(viz: Visualization, save: Boolean = true) {
        if (_uiState.value.isVizLocked) return
        performSelectVisualization(viz, save)
    }

    fun selectVisualization(viz: Visualization) {
        selectVisualization(viz, save = true)
    }

    /** Unguarded selection, for the lock engage/release paths that own the lock themselves. */
    private fun performSelectVisualization(viz: Visualization, save: Boolean) {
        // A caller can hold a stale reference to a viz whose song has already moved on (e.g. a
        // dropdown item composed a frame before the song changed and the lock released). Refuse
        // it outright rather than trusting isVizLocked, which may already be false again.
        if (viz.exclusiveToSong != null && viz.exclusiveToSong != currentSongTitle) return

        if (_currentViz.value == viz) {
            if (save && _uiState.value.isRandomVizMode) {
                _uiState.update { it.copy(isRandomVizMode = false) }
                scope.launch(dispatcherProvider.default) {
                    appPreferencesRepository.update { it.copy(randomVizMode = false, lastVizId = persistedIdOf(viz)) }
                }
            }
            return
        }

        // Lifecycle mutations (onDeactivate/onActivate/setKnob) are confined to main:
        // Visualization internal state is owned by the main-thread Compose frame loop.
        // Preference persistence is dispatched to default inside withContext to keep I/O
        // off the main thread.
        scope.launch(dispatcherProvider.main) {
            _currentViz.value.onDeactivate()
            viz.onActivate()

            dynamicEffectsJob?.cancel()
            dynamicEffectsJob = null

            _currentViz.value = viz

            if (viz is DynamicVisualization) {
                // liquidEffectsFlow collection only writes to a thread-safe MutableStateFlow,
                // so it is safe to run on the default dispatcher.
                dynamicEffectsJob = scope.launch(dispatcherProvider.default) {
                    viz.liquidEffectsFlow.collect { effects ->
                         _uiState.update { it.copy(liquidEffects = effects) }
                    }
                }
            }

            updateState()

            if (save) {
                _uiState.update { it.copy(isRandomVizMode = false) }
                withContext(dispatcherProvider.default) {
                    appPreferencesRepository.update { it.copy(randomVizMode = false, lastVizId = persistedIdOf(viz)) }
                }
            }
        }
    }

    /** A song-exclusive viz is never written to prefs: its id is meaningless without the song. */
    private fun persistedIdOf(viz: Visualization): String? =
        viz.id.takeIf { viz.exclusiveToSong == null }

    private fun setRandomMode(enabled: Boolean) {
        _uiState.update { it.copy(isRandomVizMode = enabled) }
        scope.launch(dispatcherProvider.default) {
            appPreferencesRepository.update { it.copy(randomVizMode = enabled) }
        }
        // Recorded above either way, so it takes effect once the lock releases; only the
        // immediate pick is held back while locked.
        if (enabled && !_uiState.value.isVizLocked) selectRandomVisualization()
    }

    /** No-op while locked. Never picks a song-exclusive viz. */
    private fun selectRandomVisualization() {
        if (_uiState.value.isVizLocked) return
        val candidates = sortedVisualizations.filter {
            it.id != "off" && it.exclusiveToSong == null && it != _currentViz.value
        }
        val pick = candidates.randomOrNull()
            ?: sortedVisualizations.firstOrNull { it.id != "off" && it.exclusiveToSong == null }
            ?: return
        performSelectVisualization(pick, save = false)
    }

    /** Reacts to the selected song (from [MetadataProducer.songFlow]) changing. */
    private fun onSongTitleChanged(title: String) {
        currentSongTitle = title
        val exclusiveViz = sortedVisualizations.firstOrNull { it.exclusiveToSong == title }
        val locked = _uiState.value.isVizLocked
        when {
            exclusiveViz != null && !locked -> engageLock(exclusiveViz)
            exclusiveViz == null && locked -> releaseLock()
            exclusiveViz != null && locked && exclusiveViz != _currentViz.value ->
                hopLock(exclusiveViz)
            // Same song still selected (locked or not); nothing to do. Guards against
            // re-running lifecycle calls when songFlow re-emits the same title.
        }
    }

    /**
     * Already locked, but the new song's exclusive viz differs from the current one (e.g.
     * skipping straight from one locked song to another without an unlocked song in between).
     * Hops directly to the new exclusive viz; [vizBeforeLock] is left untouched, since it still
     * names the viz to restore once locking ends for good.
     */
    private fun hopLock(exclusiveViz: Visualization) {
        _uiState.update { it.copy(visualizations = availableVisualizations()) }
        performSelectVisualization(exclusiveViz, save = false)
    }

    private fun engageLock(exclusiveViz: Visualization) {
        vizBeforeLock = _currentViz.value
        _uiState.update { it.copy(isVizLocked = true, visualizations = availableVisualizations()) }
        performSelectVisualization(exclusiveViz, save = false)
    }

    private fun releaseLock() {
        val restoreTarget = vizBeforeLock?.takeIf { sortedVisualizations.contains(it) }
            ?: sortedVisualizations.first()
        vizBeforeLock = null
        _uiState.update { it.copy(isVizLocked = false, visualizations = availableVisualizations()) }
        if (_uiState.value.isRandomVizMode) {
            selectRandomVisualization()
        } else {
            performSelectVisualization(restoreTarget, save = false)
        }
    }

    /**
     * Applies a viz restored from stored preferences at startup (the caller already checked it's
     * a real, non-exclusive viz). If the lock is already held (the songFlow collector can win
     * the startup race against preference loading), this viz becomes the restore target for when
     * the lock releases instead of being silently dropped; the current (locked) selection and its
     * lifecycle are left untouched. Must run on main: it reads/writes [vizBeforeLock] and
     * [VizUiState.isVizLocked], both otherwise only touched from main (the songFlow collector and
     * lock engage/release), so this call has to join that single-threaded queue rather than race
     * it from the default-dispatched prefs load.
     */
    private fun restoreStoredViz(viz: Visualization) {
        if (_uiState.value.isVizLocked) {
            vizBeforeLock = viz
        } else {
            performSelectVisualization(viz, save = false)
        }
    }

    fun onKnob1Change(value: Float) {
        _currentViz.value.setKnob1(value)
        _uiState.update { it.copy(knob1Value = value) }
    }

    fun onKnob2Change(value: Float) {
        _currentViz.value.setKnob2(value)
        _uiState.update { it.copy(knob2Value = value) }
    }

    private fun onToggleSignalViz(enabled: Boolean) {
        _uiState.update { it.copy(signalVizEnabled = enabled) }
        scope.launch(dispatcherProvider.default) {
            appPreferencesRepository.update { it.copy(signalVizEnabled = enabled) }
        }
    }

    private fun updateState() {
        _uiState.update {
            it.copy(
                selectedViz = _currentViz.value,
                showKnobs = _currentViz.value.id != "off",
                liquidEffects = _currentViz.value.liquidEffects,
                visualizations = availableVisualizations(),
            )
        }
    }
    
    companion object {
        fun previewFeature(state: VizUiState = VizUiState(
            selectedViz = OffViz(),
            visualizations = listOf(OffViz()),
            showKnobs = false
        )): VizFeature =
            object : VizFeature {
                override val stateFlow: StateFlow<VizUiState> = MutableStateFlow(state)
                override val actions: VizPanelActions = VizPanelActions.EMPTY
            }

        @Composable
        fun feature(): VizFeature =
            synthFeature<VizFeature>()
    }
}
