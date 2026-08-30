package org.balch.orpheus.djapp

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Docks [panels] around a centre stage: Pulsar in the middle, everything else stacked down the
 * left and right edges. The centre stage holds only Pulsar, so with Pulsar off the
 * visualization behind shows through the whole middle of the screen.
 *
 * Panels keep their own content height. Each column centres its stack vertically rather than
 * stretching panels to fill, so the Timer and Mixer render at their natural proportions
 * instead of being pulled tall.
 */
@Composable
fun DjPanelDock(
    panels: List<DjRoute>,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    panelContent: @Composable (DjRoute, Modifier) -> Unit,
) {
    val dock = assignDock(panels)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            // Horizontal only: DjAppScreen sandwiches this dock between DjTvTopBar and
            // DjTvBottomBar, which already reserve the vertical overscan margin on their own
            // Top/Bottom edges (see platformSafeAreaInsets) — padding vertical here too only
            // shrank docked panels for a crop that can't happen twice.
            .padding(horizontal = maxWidth * OverscanFraction),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        DockColumn(
            panels = dock.left,
            gap = gap,
            modifier = Modifier.weight(EdgeColumnWidth).fillMaxHeight(),
            panelContent = panelContent,
        )

        // The centre stage always reserves its share, docked or not, so the edge columns keep
        // a stable width and panels do not jump sideways when Pulsar is toggled.
        Box(
            modifier = Modifier.weight(CentreStageWidth).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            dock.centre?.let { route ->
                panelContent(route, Modifier.fillMaxWidth().wrapContentHeight())
            }
        }

        DockColumn(
            panels = dock.right,
            gap = gap,
            modifier = Modifier.weight(EdgeColumnWidth).fillMaxHeight(),
            panelContent = panelContent,
        )
    }
    }
}

/** One edge column: panels at their content height, stack centred vertically. */
@Composable
private fun DockColumn(
    panels: List<DjRoute>,
    gap: Dp,
    modifier: Modifier = Modifier,
    panelContent: @Composable (DjRoute, Modifier) -> Unit,
) {
    // Box centres the stack; the scrollable Column caps at the column height, so a stack
    // that fits is centred and one that overflows scrolls instead of clipping its controls.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Children of a vertical scroller are measured with an infinite max height, which a
        // panel that scrolls internally (Vibe Info) rejects outright. Capping each panel at
        // the column height hands the inner scroller a finite constraint again.
        val columnHeight = maxHeight

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            panels.forEach { route ->
                panelContent(
                    route,
                    Modifier.fillMaxWidth().heightIn(max = columnHeight).wrapContentHeight(),
                )
            }
        }
    }
}
