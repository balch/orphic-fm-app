package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.panels.HeaderPanelActions
import org.balch.orpheus.ui.panels.HeaderPanelUiState

/** Below this window width the rail starts hidden, until the user toggles it. */
private val RailDefaultMinWidth = 1200.dp

/**
 * The desktop panel area: recents strip (or its handle) on top, the rail beside the slots. The screen
 * draws search over the whole window and shares [nav]. Nothing renders until the slots are restored.
 */
@Composable
fun PanelNavigationLayout(
    panels: Map<PanelId, FeaturePanel>,
    state: HeaderPanelUiState,
    actions: HeaderPanelActions,
    nav: PanelNavState,
    onDialogActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val catalog = rememberPanelNavCatalog(panels)

    Box(modifier.fillMaxSize().onSizeChanged { measured = it }) {
        // The rail default depends on the width, and a refit before the restore would lose slots and
        // pins, so wait for both rather than flip capacity.
        if (measured == IntSize.Zero || !state.restored) return@Box

        val windowWidth = with(LocalDensity.current) { measured.width.toDp() }
        val railShown = state.railVisible ?: (windowWidth >= RailDefaultMinWidth)
        val toggleRail = { actions.setRailVisible(!railShown) }
        val openSearch = { nav.openSearch(autoFocus = false) }

        Column(Modifier.fillMaxSize()) {
            if (state.recentsStripVisible) {
                RecentsStrip(
                    entries = catalog.entriesById,
                    colors = catalog.colors,
                    slots = state.slots,
                    railVisible = railShown,
                    onOpen = nav::open,
                    onSearch = openSearch,
                    onToggleRail = toggleRail,
                    onCollapse = { actions.setRecentsStripVisible(false) },
                )
            } else {
                RecentsHandle(
                    onExpand = { actions.setRecentsStripVisible(true) },
                    onSearch = openSearch,
                    onToggleRail = toggleRail,
                    modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (railShown) {
                    PanelRail(
                        entries = catalog.entries,
                        colors = catalog.colors,
                        openIds = state.slots.openIds.toSet(),
                        onOpen = nav::open,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
                PanelSlotsArea(
                    panels = panels,
                    entries = catalog.entriesById,
                    state = state,
                    actions = actions,
                    pulse = nav.pulse,
                    onDialogActiveChange = onDialogActiveChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
