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
import org.balch.orpheus.core.sortPanels
import org.balch.orpheus.core.synthViewModel
import org.balch.orpheus.features.ai.PanelExpansionEventBus

/**
 * UI state for the HeaderPanel, containing expansion states for all panels.
 */
@Immutable
data class HeaderPanelUiState(
    val expandedPanels: PersistentMap<PanelId, Boolean> = persistentMapOf()
) {
    /**
     * Check if a panel is expanded.
     */
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
}

private data class HeaderIntent(val panelId: PanelId, val expanded: Boolean)

/**
 * ViewModel for the HeaderPanel, managing panel expansion/collapse state.
 *
 * Uses declarative merge -> scan -> stateIn pattern for consistency with other ViewModels.
 */
@Inject
@ViewModelKey(HeaderViewModel::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HeaderViewModel(
    panelExpansionEventBus: PanelExpansionEventBus,
    panels: Set<FeaturePanel>
) : ViewModel(), HeaderFeature {

    private val log = logging("HeaderViewModel")

    override val sortedPanels: List<FeaturePanel> = sortPanels(panels)

    private val defaultExpansion: PersistentMap<PanelId, Boolean> =
        panels.associate { it.panelId to it.defaultExpanded }.toPersistentMap()

    private val uiIntents = MutableSharedFlow<HeaderIntent>(extraBufferCapacity = 64)

    private val busIntents = panelExpansionEventBus.events
        .map { event ->
            log.debug { "HeaderViewModel: ${event.panelId.id} -> ${if (event.expand) "EXPAND" else "COLLAPSE"}" }
            HeaderIntent(event.panelId, event.expand)
        }

    override val stateFlow: StateFlow<HeaderPanelUiState> =
        merge(uiIntents, busIntents)
            .scan(HeaderPanelUiState(expandedPanels = defaultExpansion)) { state, intent ->
                log.debug { "HeaderViewModel: setExpanded(${intent.panelId.id}, ${intent.expanded})" }
                state.copy(expandedPanels = state.expandedPanels.put(intent.panelId, intent.expanded))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = HeaderPanelUiState(expandedPanels = defaultExpansion)
            )

    override val actions = HeaderPanelActions(
        setExpanded = { panelId, expanded -> uiIntents.tryEmit(HeaderIntent(panelId, expanded)) }
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
            }

        @Composable
        fun feature(): HeaderFeature =
            synthViewModel<HeaderViewModel, HeaderFeature>()
    }
}
