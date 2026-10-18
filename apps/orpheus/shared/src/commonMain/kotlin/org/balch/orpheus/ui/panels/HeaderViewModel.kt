package org.balch.orpheus.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.di.HeaderPanelGraph
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.ui.FactoryPanelSets
import org.balch.orpheus.ui.panels.slots.HeaderIntent
import org.balch.orpheus.ui.panels.slots.PanelSlots
import org.balch.orpheus.ui.panels.slots.reduceHeader
import org.balch.orpheus.ui.panels.slots.restoreSlots
import org.balch.orpheus.ui.panels.slots.toPrefs

/**
 * UI state for the HeaderPanel: which panels fill the slot row, the rail and recents-strip
 * toggles, and the active panel set. A null [railVisible] means the window width decides.
 * [restored] turns true once the saved slots have loaded; the slot UI and saving wait for it.
 */
@Immutable
data class HeaderPanelUiState(
    val slots: PanelSlots = PanelSlots(),
    val activePanelSet: PanelSet? = null,
    val railVisible: Boolean? = null,
    val recentsStripVisible: Boolean = true,
    val restored: Boolean = false,
) {
    fun isExpanded(panelId: PanelId): Boolean = slots.isOpen(panelId)
}

/**
 * Actions for the HeaderPanel.
 */
data class HeaderPanelActions(
    val setExpanded: (PanelId, Boolean) -> Unit,
    val open: (PanelId) -> Unit,
    val close: (PanelId) -> Unit,
    val togglePin: (PanelId) -> Unit,
    val touch: (PanelId) -> Unit,
    val setCapacity: (Int) -> Unit,
    val setRailVisible: (Boolean) -> Unit,
    val setRecentsStripVisible: (Boolean) -> Unit,
) {
    companion object {
        val EMPTY = HeaderPanelActions(
            setExpanded = { _, _ -> },
            open = {},
            close = {},
            togglePin = {},
            touch = {},
            setCapacity = {},
            setRailVisible = {},
            setRecentsStripVisible = {},
        )
    }
}

interface HeaderFeature : SynthFeature<HeaderPanelUiState, HeaderPanelActions> {
    val sortedPanels: List<FeaturePanel>
    val visiblePanels: List<FeaturePanel>

    /**
     * Resolve a [PanelSet] against registered panels.
     * Returns matched panels in declaration order, logging warnings for unresolved IDs.
     */
    fun resolvePanels(panelSet: PanelSet): List<FeaturePanel>

    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}

/**
 * Feature for the HeaderPanel, managing panel expansion/collapse state
 * and panel set visibility/ordering.
 */
@OptIn(FlowPreview::class)
@Inject
@SingleIn(FeatureScope::class)
@SynthFeatureKey(HeaderFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<HeaderFeature>())
class HeaderViewModel(
    panelExpansionEventBus: PanelExpansionEventBus,
    headerPanelGraphFactory: HeaderPanelGraph.Factory,
    panelSetRegistry: PanelSetRegistry,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : HeaderFeature {

    private val log = logging("HeaderViewModel")
    private val panels: Set<FeaturePanel> = headerPanelGraphFactory.create().featurePanels

    init {
        // Seed the shared registry with factory panel sets
        panelSetRegistry.seed(FactoryPanelSets.all)
    }

    private val panelMap: Map<PanelId, FeaturePanel> =
        panels.associateBy { it.panelId }

    // All panels in an unordered list — ordering comes from the active panel set
    override val sortedPanels: List<FeaturePanel> = panels.toList()

    private val defaultPanelSet = FactoryPanelSets.DesktopScreen

    // Replay holds intents sent before the reducer below subscribes, such as an early Restore.
    private val uiIntents = MutableSharedFlow<HeaderIntent>(replay = 64, extraBufferCapacity = 64)

    private val busIntents = panelExpansionEventBus.events
        .mapNotNull { event ->
            log.debug { "HeaderViewModel: ${event.panelId.id} -> ${if (event.expand) "EXPAND" else "COLLAPSE"}" }
            when {
                event.panelId !in panelMap -> null
                event.expand -> HeaderIntent.Open(event.panelId)
                else -> HeaderIntent.Close(event.panelId)
            }
        }

    private val panelSetIntents = panelExpansionEventBus.panelSetEvents
        .map { event ->
            log.debug { "HeaderViewModel: APPLY SET ${event.panelSet.name}" }
            HeaderIntent.ApplySet(registeredOnly(event.panelSet))
        }

    private fun registeredOnly(panelSet: PanelSet): PanelSet {
        val known = panelSet.panels.filter { PanelId(it.panelId) in panelMap }
        if (known.size != panelSet.panels.size) {
            log.warn { "Panel set '${panelSet.name}' references unknown panels" }
        }
        return panelSet.copy(panels = known)
    }

    // Not restored, so the slot UI stays blank until the saved slots replace these defaults.
    private val initialState = HeaderPanelUiState(
        slots = restoreSlots(AppPreferences(), defaultPanelSet, panelMap.keys).slots,
        activePanelSet = defaultPanelSet,
    )

    private val _state = MutableStateFlow(initialState)
    override val stateFlow: StateFlow<HeaderPanelUiState> = _state.asStateFlow()

    init {
        // Always-on reducer: folds UI, bus and panel-set intents into state whether or not the UI collects.
        scope.launch(dispatcherProvider.io) {
            merge(uiIntents, busIntents, panelSetIntents)
                .scan(initialState) { state, intent ->
                    reduceHeader(state, intent)
                }
                .collect { _state.value = it }
        }
    }

    override val visiblePanels: List<FeaturePanel>
        get() = resolvePanels(FactoryPanelSets.DesktopScreen)

    override fun resolvePanels(panelSet: PanelSet): List<FeaturePanel> {
        return panelSet.visibleIds.mapNotNull { id ->
            panelMap[id] ?: run {
                log.warn { "PanelSet '${panelSet.name}' references unregistered panel: ${id.id}" }
                null
            }
        }
    }

    override val actions = HeaderPanelActions(
        setExpanded = { panelId, expanded ->
            uiIntents.tryEmit(if (expanded) HeaderIntent.Open(panelId) else HeaderIntent.Close(panelId))
        },
        open = { uiIntents.tryEmit(HeaderIntent.Open(it)) },
        close = { uiIntents.tryEmit(HeaderIntent.Close(it)) },
        togglePin = { uiIntents.tryEmit(HeaderIntent.TogglePin(it)) },
        touch = { uiIntents.tryEmit(HeaderIntent.Touch(it)) },
        setCapacity = { uiIntents.tryEmit(HeaderIntent.SetCapacity(it)) },
        setRailVisible = { uiIntents.tryEmit(HeaderIntent.SetRailVisible(it)) },
        setRecentsStripVisible = { uiIntents.tryEmit(HeaderIntent.SetRecentsStripVisible(it)) },
    )

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════
    init {
        // Runs after panelMap, defaultPanelSet and uiIntents are initialised.
        scope.launch(dispatcherProvider.io) {
            restoreSavedExpansion()
        }
        // Debounced save, never before the restore lands, so defaults cannot overwrite saved slots.
        scope.launch(dispatcherProvider.io) {
            stateFlow.filter { it.restored }.debounce(1_000L).collect { state ->
                saveExpansionState(state)
            }
        }
    }

    private suspend fun restoreSavedExpansion() {
        val restore = restoreSlots(appPreferencesRepository.load(), defaultPanelSet, panelMap.keys)
        uiIntents.tryEmit(restore)
        log.debug { "Restored panel slots: ${restore.slots.openIds.size} open" }
    }

    private suspend fun saveExpansionState(state: HeaderPanelUiState) {
        val prefs = state.slots.toPrefs(state.railVisible, state.recentsStripVisible)
        appPreferencesRepository.update { it.copy(panelSlots = prefs) }
    }

    companion object {
        fun previewFeature(
            state: HeaderPanelUiState = HeaderPanelUiState(restored = true),
            panels: List<FeaturePanel> = emptyList()
        ): HeaderFeature =
            object : HeaderFeature {
                private val panelMap = panels.associateBy { it.panelId }
                override val stateFlow: StateFlow<HeaderPanelUiState> = MutableStateFlow(state)
                override val actions: HeaderPanelActions = HeaderPanelActions.EMPTY
                override val sortedPanels = panels
                override val visiblePanels = panels
                override fun resolvePanels(panelSet: PanelSet): List<FeaturePanel> =
                    panelSet.visibleIds.mapNotNull { panelMap[it] }
            }

        @Composable
        fun feature(): HeaderFeature =
            synthFeature<HeaderFeature>()
    }
}
