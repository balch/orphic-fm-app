package org.balch.orpheus.ui.widgets.popover

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.balch.orpheus.ui.viz.KeepPanelsAwake
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.widgets.OrpheusSlideUpSheet

/** Below this window width a pop-out is a bottom sheet instead of a popover. */
val NarrowWindow: Dp = 600.dp

/**
 * A pop-out anchored to the composable that calls it: a focusable popover under the anchor on wide
 * windows, a full-height bottom sheet on narrow ones. [content] gets `sheet` and a modifier that
 * sizes it ([popoverWidth] by [popoverHeight], kept in the window, or [sheetHeightFraction] of it).
 */
@Composable
fun AnchoredPopoverOrSheet(
    onDismiss: () -> Unit,
    popoverWidth: Dp,
    popoverHeight: Dp,
    sheetHeightFraction: Float = 0.8f,
    content: @Composable (sheet: Boolean, sizeModifier: Modifier) -> Unit,
) {
    // Layout comes from the window, decided here: BoxWithConstraints crashes inside a popup.
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { window.width.toDp() }
    val windowHeight = with(density) { window.height.toDp() }
    val narrow = window.width > 0 && windowWidth < NarrowWindow

    // The sheet and the popover are their own windows, so they would not fade with the panel that opened them.
    KeepPanelsAwake(LocalPanelIdleFade.current)
    if (narrow) {
        OrpheusSlideUpSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
            content(true, Modifier.fillMaxWidth().fillMaxHeight(sheetHeightFraction))
        }
    } else {
        val gap = with(density) { 4.dp.roundToPx() }
        val margin = with(density) { 8.dp.roundToPx() }
        val position = remember(gap, margin) { AnchorPopoverPosition(gap, margin) }
        val width = if (window.width > 0) minOf(popoverWidth, windowWidth - 24.dp) else popoverWidth
        val height = if (window.height > 0) {
            minOf(popoverHeight, windowHeight - 24.dp).coerceAtLeast(200.dp)
        } else {
            popoverHeight
        }
        Popup(
            popupPositionProvider = position,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            content(false, Modifier.width(width).height(height))
        }
    }
}

/** Under the anchor and left-aligned with it; above it when there is no room below; kept in the window. */
private class AnchorPopoverPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = anchorBounds.left.coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height - margin -> below
            above >= margin -> above
            else -> (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
