package org.balch.orpheus.djapp.variant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.DjLayout
import org.balch.orpheus.djapp.DjRoute
import org.balch.orpheus.djapp.dockDomeOverhang
import org.balch.orpheus.djapp.phoneBarOverhang
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware

/** A variant-supplied bottom tab. Editions contribute these via DI; the Core
 *  edition contributes none. `replaces` swaps this tab into an existing route's
 *  slot (e.g. AI replaces Horn); null appends at the end. */
interface DjTabContribution {
    val route: DjRoute
    val replaces: DjRoute?

    /**
     * Renders this contribution's UI. When [route].opensAsSheet is true the host keeps this invoked
     * across the whole screen (not only while open) and passes [isOpen] = "am I the active sheet?".
     * The contribution owns its own sheet chrome and renders it only while [isOpen]; [isLandscape]
     * lets it adapt the presentation and [onDismiss] clears the host's active-sheet selection.
     * [LocalStageChromeOverhang] says how far the bar's chrome reaches over the stage's bottom edge.
     *
     * Because Content stays composed while closed, a contribution can cancel in-flight work when
     * [isOpen] transitions true -> false — which covers EVERY close path (scrim/drag/back, the nav
     * item toggle, and switching to another sheet), unlike a scrim-only onDismiss. This must key off
     * the [isOpen] transition (e.g. LaunchedEffect), NOT onDispose, so a config-change recomposition
     * (which restores [isOpen] = true) does not wrongly cancel a run the user wanted to keep.
     */
    @Composable
    fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    )
}

/**
 * How far the bottom bar's raised dome and its name pill reach up over the stage's bottom edge,
 * provided around the sheet contributions. 0 outside them and with the rail.
 */
val LocalStageChromeOverhang: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/** The overhang [LocalStageChromeOverhang] carries for [layout]: the phone bar's, the dock's, or none beside a rail. */
@Composable
fun stageChromeOverhang(layout: DjLayout): Dp = when (layout) {
    DjLayout.Portrait, DjLayout.PortraitPair, is DjLayout.Tabletop -> phoneBarOverhang()
    DjLayout.Landscape -> 0.dp
    DjLayout.LargeScreen -> dockDomeOverhang(LocalTelevisionHardware.current)
}
