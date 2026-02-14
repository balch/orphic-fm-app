package org.balch.orpheus.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.ui.FactoryPanelSets
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.ai.PanelExpansionEventBus

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
}

private sealed class HeaderIntent {
    data class Single(val panelId: PanelId, val expanded: Boolean) : HeaderIntent()
    data class ApplySet(val panelSet: PanelSet) : HeaderIntent()
}

/**
 * ViewModel for the HeaderPanel, managing panel expansion/collapse state
 * and panel set visibility/ordering.
 */
@Inject
@ViewModelKey(HeaderViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HeaderViewModel(
    panelExpansionEventBus: PanelExpansionEventBus,
    panels: Set<FeaturePanel>,
    panelSetRegistry: PanelSetRegistry,
) : ViewModel(), HeaderFeature {

    private val log = logging("HeaderViewModel")

    init {
        // Seed the shared registry with factory panel sets
        panelSetRegistry.seed(FactoryPanelSets.all)
    }

    private val panelMap: Map<PanelId, FeaturePanel> =
        panels.associateBy { it.panelId }

    // All panels in an unordered list — ordering comes from the active panel set
    override val sortedPanels: List<FeaturePanel> = panels.toList()

    private val defaultPanelSet = FactoryPanelSets.All

    private val uiIntents = MutableSharedFlow<HeaderIntent>(extraBufferCapacity = 64)

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
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = initialState
            )

    override val visiblePanels: List<FeaturePanel>
        get() {
            val state = stateFlow.value
            return state.visiblePanelIds.mapNotNull { panelMap[it] }
        }

    override val actions = HeaderPanelActions(
        setExpanded = { panelId, expanded ->
            uiIntents.tryEmit(HeaderIntent.Single(panelId, expanded))
        }
    )

    companion object {
        fun previewFeature(
            state: HeaderPanelUiState = HeaderPanelUiState(),
            panels: List<FeaturePanel> = emptyList()
        ): HeaderFeature =
            object : HeaderFeature {
                override val stateFlow: StateFlow<HeaderPanelUiState> = MutableStateFlow(state)
                override val actions: HeaderPanelActions = HeaderPanelActions.EMPTY
                override val sortedPanels = panels
                override val visiblePanels = panels
            }

        @Composable
        fun feature(): HeaderFeature =
            synthViewModel<HeaderViewModel, HeaderFeature>()
    }
}
