package org.balch.orpheus.djapp

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
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
