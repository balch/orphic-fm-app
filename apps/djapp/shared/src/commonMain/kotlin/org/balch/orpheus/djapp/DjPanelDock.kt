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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
    // panels is a plain List (structurally equal each recomposition when unchanged), but
    // assignDock() still allocates three fresh lists every call — memoizing avoids doing that
    // on every recomposition this dock isn't itself the cause of (e.g. an unrelated knob drag
    // in a docked panel).
    val dock = remember(panels) { assignDock(panels) }

    // A plain Box, NOT BoxWithConstraints: BoxWithConstraints is backed by SubcomposeLayout,
    // which defers/redoes composition of its content on every measure — on a desktop window
    // drag-resize that meant every docked panel recomposed on every single resize frame, just
    // to recompute one padding value. Expressing the overscan margin as a width FRACTION
    // instead of `maxWidth * OverscanFraction` removes the only reason this needed `maxWidth`
    // at all, so an ordinary (single measure pass, no subcomposition) Box works. contentAlignment
    // = Center reproduces the old symmetric-padding look: Box still reports the full available
    // size to its parent (it's the one bounded by `modifier`), and centers the now-narrower Row
    // inside it, landing the same margin on both edges that `padding(maxWidth * OverscanFraction)`
    // produced.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            // Horizontal only: DjAppScreen sandwiches this dock between DjTvTopBar and
            // DjTvBottomBar, which already reserve the vertical overscan margin on their own
            // Top/Bottom edges (see platformSafeAreaInsets) — insetting vertically here too
            // only shrank docked panels for a crop that can't happen twice.
            .fillMaxWidth(1f - 2f * OverscanFraction),
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
                // assignDock() identifies each column's members by list position, and that
                // position shifts for every panel after the one toggled (parity flips).
                // forEach identifies children the same way by default, so without an explicit
                // key a toggle tore down and rebuilt every untouched panel below it — losing
                // remembered state, scroll position, and D-pad focus. Keying on the route ties
                // identity to the panel itself instead of its slot.
                key(route) {
                    panelContent(
                        route,
                        Modifier.fillMaxWidth().heightIn(max = columnHeight).wrapContentHeight(),
                    )
                }
            }
        }
    }
}
