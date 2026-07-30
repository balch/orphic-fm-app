package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Hand-rolled thumb, because Android is the one target without `VerticalScrollbar` (Compose
 * Multiplatform ships it from `skikoMain`, which covers JVM/WASM/iOS but not Android).
 *
 * Indicator only, not draggable: on a touch screen an 8dp-wide drag target would be a miss
 * factory, and widening it to something thumb-sized would take that area away from the rows this
 * bar overlays, since the topmost sibling wins the hit test. The list is already the scroll
 * surface, and a fling clears Pulsar's whole catalog in one gesture.
 *
 * [state] is read inside the draw lambda rather than during composition, so scrolling invalidates
 * only the draw phase. That restraint matters for this widget's main caller: [EnumDropdown] exists
 * precisely because Material3's eager menu dropped a frame opening Pulsar's 47-vibe list, and a
 * scrollbar that recomposed on every scrolled pixel would hand that cost straight back.
 *
 * Assumes uniform row heights, which holds for every current caller (single-line, ellipsized rows
 * at a fixed minimum height). With ragged rows the thumb still lands correctly at both extremes
 * but drifts in the middle.
 */
@Composable
internal actual fun PlatformVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
    color: Color,
) {
    val thumbColor = color.copy(alpha = OrpheusScrollbarDefaults.IdleAlpha)

    Canvas(modifier.width(OrpheusScrollbarDefaults.Thickness)) {
        val info = state.layoutInfo
        val rowHeight = info.visibleItemsInfo.firstOrNull()?.size ?: return@Canvas
        if (rowHeight <= 0) return@Canvas

        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val content = rowHeight.toFloat() * info.totalItemsCount
        // Not the visibility check — OrpheusVerticalScrollbar already established the list
        // overflows before composing this. This guards the `content - viewport` divide below,
        // which the uniform-row estimate can still drive to zero or negative when real rows are
        // ragged enough to disagree with it. Without it, `progress` goes NaN and the thumb is
        // drawn at a NaN offset.
        if (content <= viewport) return@Canvas

        val track = size.height
        // coerceAtMost keeps the floor below the ceiling; coerceIn throws if min exceeds max, and
        // a track shorter than MinThumbLength is reachable on a cramped menu.
        val minThumb = OrpheusScrollbarDefaults.MinThumbLength.toPx().coerceAtMost(track)
        val thumb = (track * viewport / content).coerceIn(minThumb, track)

        val scrolled =
            state.firstVisibleItemIndex.toFloat() * rowHeight + state.firstVisibleItemScrollOffset
        // Travel is scaled across (track - thumb), not the whole track, so the thumb's *bottom*
        // edge lands flush at the end of the list instead of overshooting it.
        val progress = (scrolled / (content - viewport)).coerceIn(0f, 1f)

        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x = 0f, y = (track - thumb) * progress),
            size = Size(width = size.width, height = thumb),
            cornerRadius = CornerRadius(OrpheusScrollbarDefaults.ThumbCornerRadius.toPx()),
        )
    }
}
