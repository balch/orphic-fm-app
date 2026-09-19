package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Minimum width for TV mode. A 1080p Android TV reports roughly 960x540dp at density 2.0,
 * not 1920x1080, so a higher-looking threshold would never fire on the target device.
 */
val LargeScreenMinWidth: Dp = 900.dp

/** Minimum height for TV mode, which keeps phone landscape (around 412dp tall) out. */
val LargeScreenMinHeight: Dp = 500.dp

enum class DjLayoutMode {
    /** Header, Pulsar, one selected panel, bottom nav bar. */
    Portrait,

    /** Pulsar left, header and one selected panel right, nav rail. */
    Landscape,

    /** Visualization fills the screen, panels dock around the perimeter, rail toggles them. */
    LargeScreen,
}

/**
 * Whether the host allows TV mode at all. Desktop sets this from the window's fullscreen
 * state, so a merely wide window keeps the landscape layout; the size threshold alone would
 * flip a resized desktop window into TV mode unasked. Every other platform leaves it true
 * and is gated by size only.
 */
val LocalTvModeAllowed = compositionLocalOf { true }


fun determineLayoutMode(
    width: Dp,
    height: Dp,
    tvModeAllowed: Boolean = true,
): DjLayoutMode = when {
    tvModeAllowed && width >= LargeScreenMinWidth && height >= LargeScreenMinHeight ->
        DjLayoutMode.LargeScreen
    width > height -> DjLayoutMode.Landscape
    else -> DjLayoutMode.Portrait
}

/**
 * Minimum portrait width for two panels side by side under Pulsar. Set by rendering, not by
 * reasoning: Timer's flip clock is fixed-width and pushes its edge knobs out of a narrow panel.
 * Swept in `renderPortraitPair`, a Mix + Timer pair clips at 660dp, fits with zero margin at 680dp
 * and fits cleanly at 700dp. 700 rather than 680 because the app passes the user's fontScale
 * through, so a panel with no margin at default text size clips at the first larger setting.
 *
 * A Galaxy Z Fold held upright and unfolded measures 752dp and clears this comfortably; phones
 * (360-412dp) stay well below.
 */
val PortraitPairMinWidth: Dp = 700.dp

/**
 * Whether portrait has room for the bottom pair. Deliberately a separate flag rather than a new
 * [DjLayoutMode]: callers test `layoutMode != DjLayoutMode.Portrait` to mean "landscape", so a
 * new member would silently land wide portrait in the rail and the two-column landscape layout.
 */
fun fitsPortraitPair(mode: DjLayoutMode, width: Dp): Boolean =
    mode == DjLayoutMode.Portrait && width >= PortraitPairMinWidth

/**
 * Hands [content] the [DjLayoutMode] for the space this box fills, and whether portrait has room
 * for the bottom pair (see [fitsPortraitPair]); [modifier] must size the box.
 * The mode comes from the previous frame's measured size and so changes in composition. Decided
 * mid-layout (a BoxWithConstraints), a mode change disposes sheet dialogs inside the layout pass,
 * and the desktop scene then lays out the dead layer: "RootNodeOwner is already disposed".
 */
@Composable
fun DjLayoutModeBox(
    modifier: Modifier = Modifier,
    tvModeAllowed: Boolean = LocalTvModeAllowed.current,
    content: @Composable BoxScope.(mode: DjLayoutMode, portraitPair: Boolean) -> Unit,
) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { measured = it }) {
        // Nothing to lay out until the first measure reports a size.
        if (measured != IntSize.Zero) {
            val width = with(density) { measured.width.toDp() }
            val height = with(density) { measured.height.toDp() }
            val mode = determineLayoutMode(width, height, tvModeAllowed)
            content(mode, fitsPortraitPair(mode, width))
        }
    }
}
