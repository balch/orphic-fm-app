package org.balch.orpheus.djapp

/**
 * Toggles [route] in an ordered panel selection and returns the new selection.
 *
 * Switching a panel on APPENDS it, so the list's order is the order the user chose panels in,
 * and callers treat that as slot-fill order (see [assignDock]). Switching one off removes it and
 * leaves the survivors' order alone.
 *
 * When switching on would exceed [capacity], the OLDEST entry is evicted so the panel just asked
 * for always lands. Rejecting the tap instead would make a full selection feel stuck. The default
 * capacity is unbounded, which is the large-screen dock: it never evicts.
 */
fun togglePanel(
    current: List<DjRoute>,
    route: DjRoute,
    capacity: Int = Int.MAX_VALUE,
): List<DjRoute> {
    if (route in current) return current - route
    val next = current + route
    return if (next.size > capacity) next.drop(next.size - capacity) else next
}

/** How many panels the wide-portrait bottom row holds side by side. */
const val PortraitPairCapacity = 2

/**
 * Panels eligible for the wide-portrait pair. Pulsar is excluded because portrait always renders
 * it on its own above the pair, and Ends is excluded because portrait reaches the same controls
 * through Pulsar's ENDING pill. [VibeInfoTab] is included even though it opens as a sheet on a
 * phone: with room for two panels it earns a slot, and the header's info button toggles it.
 */
fun portraitPairPanels(tabs: List<DjRoute> = djTabs): List<DjRoute> =
    tabs.filterNot { it.opensAsSheet } + VibeInfoTab

/** The pair shown before the user has chosen one. */
val DefaultPortraitPair: List<DjRoute> = listOf(DjTab, MixTab)
