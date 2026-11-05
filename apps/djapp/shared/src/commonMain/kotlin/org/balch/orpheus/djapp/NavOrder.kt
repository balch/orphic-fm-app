package org.balch.orpheus.djapp

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.variant.DjEdition
import kotlin.math.min

/** One slot of the nav order: a panel's tab, or the open slot the bar leaves free. */
sealed interface NavEntry {
    data class Panel(val route: DjRoute) : NavEntry
    data object Open : NavEntry
}

/** The saved form of [NavEntry.Open]; no route label is ever this. */
const val NavOpenMarker = "_open"

/** The TV bottom bar's most slots, an even count either side of the dome. */
internal const val TvBarMaxSlots = 8

/** The panels [edition] can show in its nav, in default order. */
fun navPanels(edition: DjEdition): List<DjRoute> = when (edition) {
    DjEdition.AI -> listOf(DjTab, MixTab, PulsarTab, AiTab, TimerTab, HornTab, CowbellTab)
    DjEdition.OG -> listOf(DjTab, MixTab, PulsarTab, HornTab, TimerTab, CowbellTab)
}

fun defaultNavOrder(panels: List<DjRoute>): List<NavEntry> =
    listOf(NavEntry.Open) + panels.map(NavEntry::Panel)

/** The default OG order, which the scaffold and the TV chrome use until told otherwise. */
internal val OgNavOrder: List<NavEntry> = defaultNavOrder(navPanels(DjEdition.OG))

/** Repairs a saved order: unknown labels and repeats drop, one open slot, missing panels appended. */
fun restoreNavOrder(saved: List<String>?, panels: List<DjRoute>): List<NavEntry> {
    if (saved == null) return defaultNavOrder(panels)
    val byLabel = panels.associateBy { it.label }
    val seen = mutableSetOf<DjRoute>()
    var hasOpen = false
    val kept = buildList {
        for (label in saved) {
            if (label == NavOpenMarker) {
                if (!hasOpen) add(NavEntry.Open)
                hasOpen = true
                continue
            }
            val route = byLabel[label] ?: continue
            if (seen.add(route)) add(NavEntry.Panel(route))
        }
    }
    val missing = panels.filter { it !in seen }.map(NavEntry::Panel)
    return (if (hasOpen) kept else listOf(NavEntry.Open) + kept) + missing
}

fun List<NavEntry>.toSaved(): List<String> = map {
    when (it) {
        is NavEntry.Panel -> it.route.label
        NavEntry.Open -> NavOpenMarker
    }
}

/** The panel [layout] never lists in its nav: the TV docks Pulsar, the rest render it always. */
fun navDrops(layout: DjLayout): Set<DjRoute> = when (layout) {
    DjLayout.LargeScreen -> setOf(TimerTab)
    else -> setOf(PulsarTab)
}

/** The bar's two halves around the dome, and the panels that do not fit (reached by the chips). */
data class NavBar(
    val left: List<NavEntry>,
    val right: List<NavEntry>,
    val offBar: List<DjRoute>,
) {
    val full: Boolean get() = offBar.isNotEmpty()

    /** The tabs on the bar, left to right, skipping the open slot. */
    val panels: List<DjRoute> get() = (left + right).filterIsInstance<NavEntry.Panel>().map { it.route }
}

/** An even slot count, rounding odd panel counts up to leave one open slot. */
fun barSlotCount(panelCount: Int, maxEvenSlots: Int): Int =
    min(maxEvenSlots, panelCount + panelCount % 2)

/** One tab or the dome; a 360dp phone fits today's five columns. */
val NavColumnWidth = 72.dp

/** Even tab slots in [barWidth]: the columns that fit, less the dome, floored to even, at least 4. */
fun maxEvenSlots(barWidth: Dp): Int {
    val columns = (barWidth / NavColumnWidth).toInt()
    return ((columns - 1) / 2 * 2).coerceAtLeast(4)
}

fun navBar(order: List<NavEntry>, drops: Set<DjRoute>, maxEvenSlots: Int): NavBar {
    val kept = order.filter { it !is NavEntry.Panel || it.route !in drops }
    val panels = kept.filterIsInstance<NavEntry.Panel>()
    val slots = barSlotCount(panels.size, maxEvenSlots)
    val shown: List<NavEntry>
    val offBar: List<DjRoute>
    if (panels.size >= slots) {
        shown = panels.take(slots)
        offBar = panels.drop(slots).map { it.route }
    } else {
        shown = kept
        offBar = emptyList()
    }
    return NavBar(shown.take(slots / 2), shown.drop(slots / 2), offBar)
}

/** The rail's tabs in order, the first [capacity] shown and the rest off the rail; [NavEntry.Open] is skipped. */
fun railTabs(
    order: List<NavEntry>,
    drops: Set<DjRoute>,
    capacity: Int,
): Pair<List<DjRoute>, List<DjRoute>> {
    val routes = order.filterIsInstance<NavEntry.Panel>().map { it.route }.filter { it !in drops }
    return routes.take(capacity) to routes.drop(capacity)
}

/** The most tabs the compact rail fits in [railContentHeight], never under four. */
fun railTabCapacity(railContentHeight: Dp, panelCount: Int, labelLine: Dp): Int {
    val floor = min(4, panelCount)
    return (panelCount downTo floor).firstOrNull {
        railMinHeight(RailTransport.Compact, it, labelLine) <= railContentHeight
    } ?: floor
}

/** Exchanges [a] and [b] in the full list; unchanged if either is missing or they are the same. */
fun swapEntries(order: List<NavEntry>, a: NavEntry, b: NavEntry): List<NavEntry> {
    val i = order.indexOf(a)
    val j = order.indexOf(b)
    if (a == b || i < 0 || j < 0) return order
    return order.toMutableList().also { it[i] = b; it[j] = a }
}

/** What the bar's gestures call: a chip swap, a tab-on-tab exchange, and opening an off-bar panel. */
class NavActions(
    val onChipSwap: (slot: DjRoute, chip: DjRoute) -> Unit,
    val onExchange: (NavEntry, NavEntry) -> Unit,
    val onOpenOffBar: (DjRoute) -> Unit,
)
