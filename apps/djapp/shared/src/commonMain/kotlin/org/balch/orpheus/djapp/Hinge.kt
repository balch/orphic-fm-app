package org.balch.orpheus.djapp

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A horizontal fold, in window pixels as the platform reports it. Converted to dp where it is used,
 * so it always agrees with the density in the tree. Production code builds it only through
 * [tabletopHingeOf]; tests and the render harness construct it directly.
 */
data class Hinge(val topPx: Int, val bottomPx: Int)

/** The tabletop hinge, provided only by Android's MainActivity; null everywhere else. */
val LocalHinge = compositionLocalOf<Hinge?> { null }

/**
 * Smallest usable height for either half. Rejects a hinge on the window edge (Samsung's flex panel
 * puts the fold there) and any split too tight for the flat half's header row, panel and nav bar.
 * Swept in `renderTabletop` with Pulsar alone above the crease: 330dp is the smallest clean half.
 */
val TabletopMinHalfHeight: Dp = 330.dp

/** Whether a fold from [hingeTop] to [hingeBottom] leaves two usable halves in [windowHeight]. */
fun splitsIntoUsableHalves(hingeTop: Dp, hingeBottom: Dp, windowHeight: Dp): Boolean =
    hingeTop >= TabletopMinHalfHeight && windowHeight - hingeBottom >= TabletopMinHalfHeight

/**
 * The tabletop [Hinge] for a fold, or null when it is not a usable tabletop split. Takes
 * primitives rather than a platform fold type so the rule is tested on every target.
 */
fun tabletopHingeOf(
    halfOpened: Boolean,
    horizontal: Boolean,
    separating: Boolean,
    topPx: Int,
    bottomPx: Int,
    windowHeightPx: Int,
    density: Float,
): Hinge? {
    if (!halfOpened || !horizontal || !separating || density <= 0f) return null
    val usable = splitsIntoUsableHalves(
        hingeTop = (topPx / density).dp,
        hingeBottom = (bottomPx / density).dp,
        windowHeight = (windowHeightPx / density).dp,
    )
    return if (usable) Hinge(topPx, bottomPx) else null
}
