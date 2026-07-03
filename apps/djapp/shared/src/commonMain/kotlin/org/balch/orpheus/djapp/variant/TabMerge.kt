package org.balch.orpheus.djapp.variant

import org.balch.orpheus.djapp.DjRoute

/** Pure tab-list resolution: apply each contribution to the base list. */
fun mergeTabContributions(
    base: List<DjRoute>,
    contributions: List<DjTabContribution>,
): List<DjRoute> {
    var result = base
    for (c in contributions) {
        val replaces = c.replaces
        result = if (replaces == null) result + c.route
                 else result.map { if (it == replaces) c.route else it }
    }
    return result
}
