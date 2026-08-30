package org.balch.orpheus.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.TvGlassEnabled
import org.balch.orpheus.ui.infrastructure.panelGlassChrome
import org.balch.orpheus.ui.infrastructure.tvFocusRegionBorder
import org.balch.orpheus.ui.theme.lighten

/**
 * When `true`, [CollapsibleColumnPanel] hides its collapsed header strip and reduces padding,
 * equivalent to `showCollapsedHeader = false`. Used by compact portrait mode.
 */
val LocalCompactMode = staticCompositionLocalOf { false }

/**
 * Width of a docked panel's region-focus border on TV — [tvFocusRegionBorder]'s own 2.dp default
 * (tuned for the top/bottom bars' own thinner outline) doesn't read from couch distance around a
 * whole panel. Picked by rendering DjLayoutRenderHarness's renderRegionFocusBorder at TV scale.
 */
private val TvPanelFocusBorderWidth = 5.dp

/**
 * Collapsible settings panel for the left side of top row.
 * Shows a persistent vertical header strip on the left.
 * Applies liquid blur effect when LiquidState is provided via LocalLiquidState.
 *
 * When collapsed: only shows 28dp header strip with vertical title
 * When expanded: shows header strip + content area with expandedTitle at top
 * 
 * @param title Short title shown vertically when collapsed (e.g., "VIZ")
 * @param expandedTitle Optional title shown at top of content when expanded (e.g., "Background")
 *                      If null, no header is shown in content area
 * @param fillHeight When true (the default) the panel fills its parent's height and centres
 *                   content between two weighted spacers. Set false to size to content
 *                   instead, which the DJ app's TV dock needs so panels keep their own
 *                   proportions rather than being pulled tall with a dead band.
 */
@Composable
fun CollapsibleColumnPanel(
    modifier: Modifier = Modifier,
    title: String,
    expandedTitle: String? = null,
    color: Color,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    initialExpanded: Boolean = false,
    showCollapsedHeader: Boolean = true,
    fillHeight: Boolean = true,
    backgroundContent: (@Composable () -> Unit)? = null,
    // Preview seam only: draws the same region-focus border [tvFocusRegionBorder] would, without
    // needing a real shared TvFocusRegionHolder + D-pad focus event to drive it. Every real call
    // site leaves this false.
    previewRegionFocused: Boolean = false,
    // Preview/render-harness seam only: scales the SAME preview-only border's alpha, so a test can
    // show what a partially-faded TvFocusRegionHolder.alpha looks like without driving a real
    // holder+token+coroutine (real Compose focus can't be driven across a jvmTest's module
    // boundary). Only meaningful when previewRegionFocused is true; every real call site leaves
    // this at its default.
    previewRegionFocusAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    var internalExpanded by remember { mutableStateOf(initialExpanded) }
    val effectiveExpanded = isExpanded ?: internalExpanded
    val effectiveShowCollapsedHeader = showCollapsedHeader && !LocalCompactMode.current

    val toggleExpanded = {
        val next = !effectiveExpanded
        if (isExpanded == null) {
            internalExpanded = next
        }
        onExpandedChange?.invoke(next)
    }

    val liquidState = LocalLiquidState.current
    val collapsedWidth = 28.dp

    val shape = RoundedCornerShape(8.dp)

    // Apply liquid effect
    val effects = LocalLiquidEffects.current

    // Region-focus border: null everywhere except the DJ app's TV layout (see
    // DjAppScreen.kt), where at most one container's border is visible at a time — see
    // tvFocusRegionBorder's doc for why a single shared holder is what guarantees that. The
    // token identifies THIS panel instance; onFocusChanged only fires when focus actually
    // enters/leaves the panel's subtree (a focus group observes the whole subtree, so this needs
    // no manual threading through content()), and tvFocusRegionBorder reads the holder inside the
    // draw phase — neither one recomposes this panel or its content when focus moves.
    val focusRegion = LocalTvFocusRegion.current
    val focusToken = remember { Any() }
    // Brightened selected-visualization accent, not this panel's own [color]: the panel's own
    // accent doesn't reliably stand out against its own chrome (same hue as the fill/bevel it
    // sits on), and using the panel's own color would disagree with the viz-following language
    // the TV top/bottom bars now use. lighten() gives it enough pop over a bright/busy
    // visualization to read as "this is the one with focus" at a glance from a couch.
    val focusBorderColor = effects.title.titleColor.lighten(0.2f)

    // Off real TV hardware, glass is always on (unchanged look) — this is a HARDWARE check, not
    // LocalTvFocusChrome's layout check, so a wide/fullscreen desktop window entering the same
    // TV/LargeScreen layout still keeps glass. On TV hardware, TvGlassEnabled is the single switch
    // that turns every docked panel's blur/translucency into a flat opaque fill for a real-device
    // frame-rate A/B — see its doc for why.
    val glassEnabled = !LocalTelevisionHardware.current || TvGlassEnabled

    Box(
        modifier = modifier
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .focusGroup()
            .onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) }
            .panelGlassChrome(
                liquidState = liquidState,
                effects = effects,
                color = color,
                shape = shape,
                accented = effectiveExpanded,
                glassEnabled = glassEnabled,
            )
            .tvFocusRegionBorder(
                holder = focusRegion,
                token = focusToken,
                color = focusBorderColor,
                shape = shape,
                width = TvPanelFocusBorderWidth,
            )
            .then(
                if (previewRegionFocused) {
                    val alpha = focusBorderColor.alpha * previewRegionFocusAlpha
                    Modifier.border(TvPanelFocusBorderWidth, focusBorderColor.copy(alpha = alpha), shape)
                } else {
                    Modifier
                }
            )
    ) {
        // Signal visualization layer — renders behind content, inside liquid glass.
        // matchParentSize, not fillMaxSize: a decorative layer must match the panel's
        // resolved size without driving it, or it stretches the panel to the full height
        // available and defeats fillHeight = false.
        if (effectiveExpanded && backgroundContent != null) {
            Box(modifier = Modifier.matchParentSize()) {
                backgroundContent()
            }
        }

        Row {
            // [LEFT] Vertical Header Strip (Visible if enabled)
            if (effectiveShowCollapsedHeader) {
                Box(
                    modifier = Modifier
                        .width(collapsedWidth)
                        .fillMaxHeight()
                        .clickable { toggleExpanded() }
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.toList().joinToString("\n"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (effectiveExpanded) color else color.lighten(),
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (effectiveExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical =
                                if (effectiveShowCollapsedHeader) 16.dp else 4.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (expandedTitle != null) {
                        Text(
                            text = expandedTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = color,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1
                        )
                    }

                    // The spacers centre content in a full-height panel; with fillHeight off
                    // they would be the dead band we are trying to remove.
                    if (fillHeight) Spacer(modifier = Modifier.weight(1f))
                    content()
                    if (fillHeight) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ==================== PREVIEWS ====================

/**
 * The panel border states: idle (collapsed/unexpanded), accented ([panelGlassChrome]'s expanded
 * look — the state every TV-docked panel sits in permanently), and region-focused (the exclusive
 * border [tvFocusRegionBorder] draws for whichever ONE container currently holds D-pad focus,
 * forced here via [previewRegionFocused] since a real [org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder]
 * needs an actual focus event to drive it).
 */
@androidx.compose.ui.tooling.preview.Preview(name = "Panel Chrome — Idle / Accented / Region-focused")
@Composable
private fun CollapsibleColumnPanelChromeStatesPreview() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        Row(
            modifier = Modifier
                .background(Color(0xFF14141F))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollapsibleColumnPanel(
                title = "IDLE",
                color = Color(0xFF00E5FF),
                isExpanded = false,
                showCollapsedHeader = true,
                fillHeight = false,
            ) {}
            CollapsibleColumnPanel(
                title = "ACC",
                expandedTitle = "Accented",
                color = Color(0xFF00E5FF),
                isExpanded = true,
                showCollapsedHeader = false,
                fillHeight = false,
            ) {
                Text("Expanded, not focused", color = Color.White, fontSize = 11.sp)
            }
            CollapsibleColumnPanel(
                title = "FOC",
                expandedTitle = "Region-focused",
                color = Color(0xFF00E5FF),
                isExpanded = true,
                showCollapsedHeader = false,
                fillHeight = false,
                previewRegionFocused = true,
            ) {
                Text("This container holds D-pad focus", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The TV hardware path's glass fill, controlled by
 * [org.balch.orpheus.ui.infrastructure.TvGlassEnabled]. Renders under both [LocalTvFocusChrome]
 * and [LocalTelevisionHardware] provided `true`, exactly as DjAppScreen provides them on real TV
 * hardware — with the switch at its current (`false`) value this shows the flat opaque fill;
 * flipping the constant to `true` and re-running this preview shows the translucent blur it
 * replaces. (Providing only [LocalTvFocusChrome], as a wide/fullscreen desktop window does, would
 * always show the blur regardless of the switch — that is the layout/hardware distinction this
 * whole panel exists to demonstrate.)
 */
@androidx.compose.ui.tooling.preview.Preview(name = "Panel Chrome — TV hardware path (TvGlassEnabled)")
@Composable
private fun CollapsibleColumnPanelTvGlassPreview() {
    org.balch.orpheus.ui.theme.OrpheusTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTvFocusChrome provides true,
            LocalTelevisionHardware provides true,
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0xFF14141F))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CollapsibleColumnPanel(
                    title = "TV",
                    expandedTitle = "Docked on TV",
                    color = Color(0xFF7C4DFF),
                    isExpanded = true,
                    showCollapsedHeader = false,
                    fillHeight = false,
                ) {
                    Text("TvGlassEnabled = $TvGlassEnabled", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}
