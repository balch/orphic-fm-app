package org.balch.orpheus.djapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.panelGlassChrome

/** Matches the `barShape` each bar already uses for its own focus border. */
private val TvBarShape = RoundedCornerShape(8.dp)

/**
 * Whether the fullscreen/TV chrome bars carry a glass fill.
 *
 * Gated on television HARDWARE, not on the layout: a fullscreen desktop window and a real TV both
 * reach [DjLayout.LargeScreen], but only one of them can afford it. Both bars carry a comment
 * recording why the fill was deferred, and both cite the same evidence — a real-device trace where
 * the UI thread, not the GPU, was already the bottleneck. That evidence is about televisions, so
 * this turns the fill on everywhere else and leaves the television exactly as it was: a drawn
 * focus stroke and nothing that recomposes or relayouts.
 *
 * Deliberately NOT gated on GPU presence. Every television has a GPU, so a capability check
 * returns true on exactly the device the trace came from.
 */
fun shouldShowTvBarGlass(
    layout: DjLayout,
    isTelevisionHardware: Boolean,
): Boolean = when (layout) {
    DjLayout.LargeScreen -> !isTelevisionHardware
    DjLayout.Portrait, DjLayout.PortraitPair, DjLayout.Landscape, is DjLayout.Tabletop -> false
}

/**
 * The glass fill for a fullscreen chrome bar — deliberately [panelGlassChrome] and not a
 * parallel treatment of its own.
 *
 * That modifier's own kdoc says it was factored out so these bars could share it: one material
 * language for "this region has structure," whether the region is a docked panel's frame or a bar
 * wrapping nav items. Reusing it means the bars pick up the same fill, the same tint alpha and the
 * same idle/accent border the panels already have, and keeps them in step automatically.
 *
 * No new tuning constants here for the same reason. The full-bleed lens this replaced needed its
 * own numbers because a screen-sized lens behaves nothing like a panel-sized one; a bar is back in
 * panel territory, so the panel values are the right ones.
 *
 * Apply this OUTSIDE any window-inset padding. The fill should reach the physical screen edge —
 * a bottom bar whose glass stops above the safe-area inset reads as a floating slab with a gap
 * under it rather than as chrome anchored to the bezel.
 *
 * Returns an unchanged [Modifier] when [enabled] is false, so the television path adds nothing at
 * all — not even a clip or a graphics layer.
 */
@Composable
fun Modifier.tvBarGlass(enabled: Boolean): Modifier {
    if (!enabled) return this
    val effects = LocalLiquidEffects.current
    return panelGlassChrome(
        liquidState = LocalLiquidState.current,
        effects = effects,
        // The same viz-following accent both bars already tint their borders and icons with, so
        // the fill re-themes with them instead of pinning one palette.
        color = effects.title.titleColor,
        shape = TvBarShape,
        // The bars have no expanded/collapsed state of their own; they are always structural.
        accented = true,
    )
}
