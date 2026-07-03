package org.balch.orpheus.djapp.variant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.balch.orpheus.djapp.DjRoute

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
