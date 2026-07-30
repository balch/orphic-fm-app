package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Metrics every [OrpheusVerticalScrollbar] implementation draws from.
 *
 * Compose Multiplatform's own `VerticalScrollbar` ships from the `skikoMain` source set, so JVM,
 * WASM and iOS get it for free while Android does not and has to draw its own thumb. Holding the
 * numbers in one place is what keeps those two implementations looking like the same widget.
 */
internal object OrpheusScrollbarDefaults {
    /** Width of the bar. Matches skiko's `defaultScrollbarStyle().thickness`. */
    val Thickness: Dp = 8.dp

    /**
     * Floor on the thumb's length. Matches skiko's `defaultScrollbarStyle().minimalHeight`.
     *
     * Without a floor, a strictly proportional thumb over Pulsar's 47-vibe list would be a few
     * pixels tall, which reads as dirt on the screen rather than as a control.
     */
    val MinThumbLength: Dp = 16.dp

    /** Corner rounding of the thumb, as a [Dp] so the Android canvas can use it too. */
    val ThumbCornerRadius: Dp = 2.dp

    val ThumbShape: Shape = RoundedCornerShape(ThumbCornerRadius)

    /**
     * Resting opacity of the thumb.
     *
     * Well above skiko's 0.12 default: the bar's job in a dropdown is to advertise that the list
     * overflows *before* the user has tried to scroll, and it has to do that against the dark
     * [OrpheusColors.panelSurface] rather than a light one.
     */
    const val IdleAlpha: Float = 0.45f

    /** Opacity while the pointer is over the bar. Pointer platforms only. */
    const val HoverAlpha: Float = 0.75f

    /** Idle/hover crossfade duration. Matches skiko's `defaultScrollbarStyle()`. */
    const val HoverDurationMillis: Int = 300
}

/**
 * Platform-appropriate vertical scroll affordance for a [LazyListState]-driven list.
 *
 * Emits nothing at all when the content already fits its viewport, so it is safe to place
 * unconditionally over a list that may or may not overflow.
 *
 * That gate lives here rather than in each implementation because skiko's `VerticalScrollbar`
 * gates only its *paint* on whether the content overflows. Its drag, hover and click-to-page
 * pointer handlers stay installed either way, and as the topmost sibling in an overlay it wins
 * the hit test, so an invisible bar would still swallow clicks along the trailing edge of every
 * row underneath it.
 *
 * On pointer platforms the bar is draggable; on Android it is an indicator only, because an
 * 8dp-wide drag target sits far under the 48dp touch minimum and widening it would take that
 * area away from the rows it overlays.
 *
 * @param color accent to tint the thumb, applied at the widget's resting opacity.
 */
@Composable
fun OrpheusVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan,
) {
    // derivedStateOf so this invalidates only when the answer flips, not on every scrolled pixel.
    // Both properties read false before the first measure and for a list that fits; an
    // overflowing list has at least one of them true at every scroll position.
    val overflows by remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }

    if (overflows) {
        PlatformVerticalScrollbar(state = state, modifier = modifier, color = color)
    }
}

/**
 * The bar itself, composed only once [OrpheusVerticalScrollbar] has established there is
 * something to scroll. Implementations may assume the content overflows its viewport.
 */
@Composable
internal expect fun PlatformVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
    color: Color,
)
