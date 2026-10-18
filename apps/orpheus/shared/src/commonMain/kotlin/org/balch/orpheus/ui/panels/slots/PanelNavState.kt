package org.balch.orpheus.ui.panels.slots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.panels.HeaderPanelActions
import org.balch.orpheus.ui.panels.HeaderPanelUiState

/** Asks for the search overlay; [autoFocus] puts the caret in the field (keyboard, not touch). */
@Immutable
data class SearchRequest(val autoFocus: Boolean)

/** The registered panels' rail entries, colors and search candidates, in rail order. */
@Immutable
class PanelNavCatalog(
    val entries: List<PanelNavEntry>,
    val entriesById: Map<PanelId, PanelNavEntry>,
    val colors: Map<PanelId, Color>,
    val candidates: List<SearchCandidate>,
)

@Composable
fun rememberPanelNavCatalog(panels: Map<PanelId, FeaturePanel>): PanelNavCatalog = remember(panels) {
    val entries = PanelNavEntries.filter { it.id in panels }
    PanelNavCatalog(
        entries = entries,
        entriesById = entries.associateBy { it.id },
        colors = panels.mapValues { it.value.color },
        candidates = entries.map { entry ->
            val panel = panels.getValue(entry.id)
            SearchCandidate(entry, panel.label, panel.description)
        },
    )
}

/** Navigation state shared by the panel layout and the window-wide search overlay. */
@Stable
class PanelNavState internal constructor(
    private val state: State<HeaderPanelUiState>,
    private val actions: State<HeaderPanelActions>,
) {
    var pulse by mutableStateOf<PanelPulse?>(null)
        private set

    var searchRequest by mutableStateOf<SearchRequest?>(null)
        private set

    /** Opens [id] into a slot, or flashes its slot when it is already open. */
    fun open(id: PanelId) {
        if (state.value.slots.isOpen(id)) {
            pulse = PanelPulse(id, (pulse?.seq ?: 0) + 1)
            actions.value.touch(id)
        } else {
            actions.value.open(id)
        }
    }

    fun openSearch(autoFocus: Boolean) {
        searchRequest = SearchRequest(autoFocus)
    }

    fun closeSearch() {
        searchRequest = null
    }
}

@Composable
fun rememberPanelNavState(state: HeaderPanelUiState, actions: HeaderPanelActions): PanelNavState {
    val currentState = rememberUpdatedState(state)
    val currentActions = rememberUpdatedState(actions)
    return remember { PanelNavState(currentState, currentActions) }
}
