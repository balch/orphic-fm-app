package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.variant.DjEdition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavOrderTest {

    private fun p(route: DjRoute) = NavEntry.Panel(route)
    private val open = NavEntry.Open

    private val aiPanels = navPanels(DjEdition.AI)
    private val aiDefault = defaultNavOrder(aiPanels)

    // Five shown panels are what leave an open slot; the six-panel AI default fills six slots.
    private val fiveDefault = defaultNavOrder(aiPanels - CowbellTab)

    @Test
    fun `default AI phone bar matches today`() {
        val bar = navBar(aiDefault, navDrops(DjLayout.Portrait), 4)
        assertEquals(listOf(p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(AiTab), p(TimerTab)), bar.right)
        assertEquals(listOf<DjRoute>(HornTab, CowbellTab), bar.offBar)
    }

    @Test
    fun aiPanelsEndWithCowbell() {
        assertEquals(CowbellTab, navPanels(DjEdition.AI).last())
    }

    @Test
    fun ogEndsWithCowbell() {
        assertEquals(CowbellTab, navPanels(DjEdition.OG).last())
    }

    @Test
    fun savedOrderWithoutCowbellKeepsPhoneBar() {
        // What an AI phone saved before the cowbell existed: the old six panels with the open slot first.
        val saved = listOf(NavOpenMarker) + listOf(DjTab, MixTab, PulsarTab, AiTab, TimerTab, HornTab).map { it.label }
        val restored = restoreNavOrder(saved, aiPanels)
        val bar = navBar(restored, navDrops(DjLayout.Portrait), 4)
        assertEquals(listOf(p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(AiTab), p(TimerTab)), bar.right)
        assertEquals(listOf<DjRoute>(HornTab, CowbellTab), bar.offBar)
    }

    @Test
    fun `default OG phone bar keeps today's four and Cowbell off the bar`() {
        val order = defaultNavOrder(navPanels(DjEdition.OG))
        val bar = navBar(order, navDrops(DjLayout.Portrait), 4)
        assertEquals(listOf(p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(HornTab), p(TimerTab)), bar.right)
        assertEquals(listOf<DjRoute>(CowbellTab), bar.offBar)
    }

    @Test
    fun `default OG TV bar opens a slot left of the old four`() {
        val order = defaultNavOrder(navPanels(DjEdition.OG))
        val bar = navBar(order, navDrops(DjLayout.LargeScreen), 8)
        assertEquals(listOf(NavEntry.Open, p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(PulsarTab), p(HornTab), p(CowbellTab)), bar.right)
    }

    @Test
    fun `six panels on six slots leave no open slot`() {
        val bar = navBar(aiDefault, navDrops(DjLayout.Portrait), 6)
        assertEquals(listOf(p(DjTab), p(MixTab), p(AiTab)), bar.left)
        assertEquals(listOf(p(TimerTab), p(HornTab), p(CowbellTab)), bar.right)
        assertEquals(emptyList(), bar.offBar)
    }

    @Test
    fun `six panels never get more than six slots`() {
        val bar = navBar(aiDefault, navDrops(DjLayout.Portrait), 12)
        assertEquals(listOf(p(DjTab), p(MixTab), p(AiTab)), bar.left)
        assertEquals(listOf(p(TimerTab), p(HornTab), p(CowbellTab)), bar.right)
        assertEquals(emptyList(), bar.offBar)
    }

    @Test
    fun `five panels on six slots put the open slot far left`() {
        val bar = navBar(fiveDefault, navDrops(DjLayout.Portrait), 6)
        assertEquals(listOf(open, p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(AiTab), p(TimerTab), p(HornTab)), bar.right)
        assertEquals(emptyList(), bar.offBar)
    }

    @Test
    fun `dragging into the open slot moves the hole`() {
        val order = swapEntries(fiveDefault, p(HornTab), open)
        val bar = navBar(order, navDrops(DjLayout.Portrait), 6)
        assertEquals(listOf(p(HornTab), p(DjTab), p(MixTab)), bar.left)
        assertEquals(listOf(p(AiTab), p(TimerTab), open), bar.right)
    }

    @Test
    fun `chip swap exchanges in the full list`() {
        val order = swapEntries(aiDefault, p(TimerTab), p(HornTab))
        val bar = navBar(order, navDrops(DjLayout.Portrait), 4)
        assertEquals(listOf(p(AiTab), p(HornTab)), bar.right)
        assertEquals(listOf<DjRoute>(TimerTab, CowbellTab), bar.offBar)

        val tv = navDrops(DjLayout.LargeScreen)
        fun pulsarIndex(o: List<NavEntry>): Int {
            val b = navBar(o, tv, 8)
            return (b.left + b.right).indexOf(p(PulsarTab))
        }
        assertEquals(pulsarIndex(aiDefault), pulsarIndex(order))
    }

    @Test
    fun `fold keeps order`() {
        val order = swapEntries(aiDefault, p(TimerTab), p(DjTab))
        val drops = navDrops(DjLayout.Portrait)
        val before = navBar(order, drops, 6)
        navBar(order, drops, 4)
        assertEquals(before, navBar(order, drops, 6))
    }

    @Test
    fun `restore repairs saved lists`() {
        val repaired = restoreNavOrder(listOf("DJ", "Bogus", "_open", "Mix", "Mix", "_open"), aiPanels)
        assertEquals(
            listOf(p(DjTab), open, p(MixTab), p(PulsarTab), p(AiTab), p(TimerTab), p(HornTab), p(CowbellTab)),
            repaired,
        )
        assertEquals(aiDefault, restoreNavOrder(null, aiPanels))
        assertEquals(listOf(open) + aiPanels.map(::p), restoreNavOrder(emptyList(), aiPanels))
    }

    @Test
    fun `saved round trips`() {
        val order = swapEntries(aiDefault, p(HornTab), open)
        assertEquals(order, restoreNavOrder(order.toSaved(), aiPanels))
    }

    @Test
    fun `slot math`() {
        assertEquals(4, maxEvenSlots(360.dp))
        assertEquals(4, maxEvenSlots(411.dp))
        assertEquals(4, maxEvenSlots(320.dp))
        assertEquals(6, maxEvenSlots(600.dp))
        assertEquals(10, maxEvenSlots(840.dp))
        assertEquals(4, barSlotCount(5, 4))
        assertEquals(6, barSlotCount(5, 10))
        assertEquals(6, barSlotCount(6, 10))
        assertEquals(4, barSlotCount(4, 10))
    }

    @Test
    fun `the dock bar counts its slots at the screen's own size`() {
        // A TV or tablet at native density holds six.
        assertEquals(6, tvBarMaxSlots(1280.dp, canvasScale = 1f))
        // The Fold's inner display sideways: a 1280dp canvas drawn on 835dp of glass holds four.
        assertEquals(4, tvBarMaxSlots(1280.dp, canvasScale = 0.652f))
    }

    @Test
    fun `rail capacity never drops below four`() {
        val line = 16.dp
        assertEquals(4, railTabCapacity(0.dp, 5, line))
        assertEquals(5, railTabCapacity(railMinHeight(RailTransport.Compact, 5, line), 5, line))
    }
}
