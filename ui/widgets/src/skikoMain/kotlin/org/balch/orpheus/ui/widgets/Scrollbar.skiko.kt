package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * One implementation for JVM, WASM and iOS.
 *
 * Compose Multiplatform ships `VerticalScrollbar` from its own `skikoMain`, so all three targets
 * resolve the same declaration and there is nothing left to vary between them. Android is the one
 * target without it and draws its own thumb instead; see `Scrollbar.android.kt`.
 *
 * Here the bar is a real control: draggable thumb, click-to-page track, hover crossfade.
 */
@Composable
internal actual fun PlatformVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
    color: Color,
) {
    // Only the two tinted colors depend on [color]; the rest are constants, so the whole style
    // survives recomposition rather than being rebuilt on every scroll-driven frame.
    val style = remember(color) {
        defaultScrollbarStyle().copy(
            minimalHeight = OrpheusScrollbarDefaults.MinThumbLength,
            thickness = OrpheusScrollbarDefaults.Thickness,
            shape = OrpheusScrollbarDefaults.ThumbShape,
            hoverDurationMillis = OrpheusScrollbarDefaults.HoverDurationMillis,
            unhoverColor = color.copy(alpha = OrpheusScrollbarDefaults.IdleAlpha),
            hoverColor = color.copy(alpha = OrpheusScrollbarDefaults.HoverAlpha),
        )
    }

    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state),
        style = style,
    )
}
