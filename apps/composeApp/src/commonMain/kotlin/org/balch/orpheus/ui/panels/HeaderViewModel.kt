package org.balch.orpheus.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.ui.FactoryPanelSets

/**
 * UI state for the HeaderPanel, containing expansion states and active panel set.
 */
@Immutable
data class HeaderPanelUiState(
    val expandedPanels: PersistentMap<PanelId, Boolean> = persistentMapOf(),
    val activePanelSet: PanelSet? = null,
    val visiblePanelIds: List<PanelId> = emptyList(),
    val weightOverrides: Map<PanelId, Float> = emptyMap(),
) {
    fun isExpanded(panelId: PanelId): Boolean = expandedPanels[panelId] ?: false
}

/**
 * Actions for the HeaderPanel.
 */
data class HeaderPanelActions(
    val setExpanded: (PanelId, Boolean) -> Unit
) {
    companion object {
        val EMPTY = HeaderPanelActions(
            setExpanded = { _, _ -> }
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

    override val sharingStrategy: SharingStarted
        get() = SharingStarted.Eagerly
}

private sealed class HeaderIntent {
    data class Single(val panelId: PanelId, val expanded: Boolean) : HeaderIntent()
    data class ApplySet(val panelSet: PanelSet) : HeaderIntent()
}

/**
 * Feature for the HeaderPanel, managing panel expansion/collapse state
 * and panel set visibility/ordering.
 */
@OptIn(FlowPreview::class)
@Inject
@ClassKey(HeaderViewModel::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
class HeaderViewModel(
    panelExpansionEventBus: PanelExpansionEventBus,
    panels: Set<FeaturePanel>,
    panelSetRegistry: PanelSetRegistry,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) : HeaderFeature {

    private val log = logging("HeaderViewModel")

    init {
        // Seed the shared registry with factory panel sets
        panelSetRegistry.seed(FactoryPanelSets.all)
        // Restore saved panel expansion state from previous session
        scope.launch(dispatcherProvider.io) {
            restoreSavedExpansion()
        }
    }

    private val panelMap: Map<PanelId, FeaturePanel> =
        panels.associateBy { it.panelId }

    // All panels in an unordered list — ordering comes from the active panel set
    override val sortedPanels: List<FeaturePanel> = panels.toList()

    private val defaultPanelSet = FactoryPanelSets.DesktopScreen

    // replay needed: restoreSavedExpansion() emits before stateIn(Eagerly) collector
    // is dispatched, so tryEmit would silently drop values without a replay buffer.
    private val uiIntents = MutableSharedFlow<HeaderIntent>(replay = 64, extraBufferCapacity = 64)

    private val busIntents = panelExpansionEventBus.events
        .map { event ->
            log.debug { "HeaderViewModel: ${event.panelId.id} -> ${if (event.expand) "EXPAND" else "COLLAPSE"}" }
            HeaderIntent.Single(event.panelId, event.expand)
        }

    private val panelSetIntents = panelExpansionEventBus.panelSetEvents
        .map { event ->
            log.debug { "HeaderViewModel: APPLY SET ${event.panelSet.name}" }
            HeaderIntent.ApplySet(event.panelSet)
        }

    private fun buildInitialState(panelSet: PanelSet): HeaderPanelUiState {
        val registeredIds = panelMap.keys
        val validIds = panelSet.visibleIds.filter { id ->
            val exists = id in registeredIds
            if (!exists) log.warn { "Panel set '${panelSet.name}' references unknown panel: ${id.id}" }
            exists
        }
        val expanded = validIds.associateWith { it in panelSet.expandedIds }.toPersistentMap()
        return HeaderPanelUiState(
            expandedPanels = expanded,
            activePanelSet = panelSet,
            visiblePanelIds = validIds,
            weightOverrides = panelSet.weightOverrides,
        )
    }

    private val initialState = buildInitialState(defaultPanelSet)

    override val stateFlow: StateFlow<HeaderPanelUiState> =
        merge(uiIntents, busIntents, panelSetIntents)
            .scan(initialState) { state, intent ->
                when (intent) {
                    is HeaderIntent.Single -> {
                        log.debug { "HeaderViewModel: setExpanded(${intent.panelId.id}, ${intent.expanded})" }
                        state.copy(
                            expandedPanels = state.expandedPanels.put(intent.panelId, intent.expanded)
                        )
                    }
                    is HeaderIntent.ApplySet -> {
                        buildInitialState(intent.panelSet)
                    }
                }
            }
            .stateIn(
                scope = scope,
                started = this.sharingStrategy,
                initialValue = initialState
            )

    override val visiblePanels: List<FeaturePanel>
        get() {
            val state = stateFlow.value
            return state.visiblePanelIds.mapNotNull { panelMap[it] }
        }

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
            uiIntents.tryEmit(HeaderIntent.Single(panelId, expanded))
        }
    )

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════
    init {
        // Debounced save: persist expansion state 1s after the last change
        scope.launch(dispatcherProvider.io) {
            stateFlow.drop(1).debounce(1_000L).collect { state ->
                saveExpansionState(state)
            }
        }
    }

    private suspend fun restoreSavedExpansion() {
        val json = appPreferencesRepository.load().lastExpandedPanelsJson ?: return
        val saved: Map<String, Boolean> = try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to restore panel expansion state" }
            return
        }
        // Emit saved overrides as individual intents so they merge with the default state
        saved.forEach { (id, expanded) ->
            uiIntents.tryEmit(HeaderIntent.Single(PanelId(id), expanded))
        }
        log.debug { "Restored panel expansion: ${saved.count { it.value }} expanded" }
    }

    private suspend fun saveExpansionState(state: HeaderPanelUiState) {
        val map = state.expandedPanels.map { (k, v) -> k.id to v }.toMap()
        val json = Json.encodeToString<Map<String, Boolean>>(map)
        appPreferencesRepository.update { it.copy(lastExpandedPanelsJson = json) }
    }

    companion object {
        fun previewFeature(
            state: HeaderPanelUiState = HeaderPanelUiState(),
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
            synthFeature<HeaderViewModel, HeaderFeature>()
    }
}
