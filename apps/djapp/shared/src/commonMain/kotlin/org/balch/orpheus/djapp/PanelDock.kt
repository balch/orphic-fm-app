package org.balch.orpheus.djapp

/** Fraction of the width taken by each edge column. */
const val EdgeColumnWidth = 0.30f

/** Fraction of the width reserved for the centre stage. */
const val CentreStageWidth = 1f - EdgeColumnWidth * 2

/**
 * Fraction of each edge kept clear of docked panels. Televisions may overscan, cropping the
 * outer band of the picture; Android TV guidance reserves 5% a side.
 */
const val OverscanFraction = 0.05f

/**
 * Which region each docked panel occupies. Pulsar takes the centre stage; everything else
 * alternates between the two edge columns in the order it was switched on.
 */
data class DockAssignment(
    val centre: DjRoute?,
    val left: List<DjRoute>,
    val right: List<DjRoute>,
) {
    val isEmpty: Boolean get() = centre == null && left.isEmpty() && right.isEmpty()
}

/**
 * Splits [panels] into the centre stage and the two edge columns.
 *
 * Panels keep their own content height rather than being stretched to a computed rectangle,
 * so this returns a grouping rather than geometry: the columns lay their panels out by
 * intrinsic size. An earlier version returned absolute slot rects, which forced the DJ panel
 * to full column height with a large dead band and clipped Pulsar's bottom row.
 */
fun assignDock(panels: List<DjRoute>): DockAssignment {
    val centre = panels.firstOrNull { it == PulsarTab }
    val edges = panels.filterNot { it == PulsarTab }
    return DockAssignment(
        centre = centre,
        left = edges.filterIndexed { index, _ -> index % 2 == 0 },
        right = edges.filterIndexed { index, _ -> index % 2 == 1 },
    )
}
