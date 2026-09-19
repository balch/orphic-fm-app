package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelSelectionTest {

    @Test
    fun switchingOnAppendsInTheOrderChosen() {
        val afterDj = togglePanel(emptyList(), DjTab)
        val afterMix = togglePanel(afterDj, MixTab)
        assertEquals(listOf(DjTab, MixTab), afterMix)
    }

    @Test
    fun switchingOffRemovesAndKeepsTheRestInOrder() {
        val selection = listOf(DjTab, MixTab, HornTab)
        assertEquals(listOf(DjTab, HornTab), togglePanel(selection, MixTab))
    }

    @Test
    fun aFullPairEvictsTheOldestSoTheNewPanelLands() {
        // The FIFO rule: DJ was chosen first, so Horn displaces DJ, not Mix.
        val pair = listOf(DjTab, MixTab)
        assertEquals(
            listOf(MixTab, HornTab),
            togglePanel(pair, HornTab, capacity = PortraitPairCapacity),
        )
    }

    @Test
    fun repeatedTapsKeepCyclingThroughTheOldest() {
        var pair = listOf(DjTab, MixTab)
        pair = togglePanel(pair, HornTab, capacity = PortraitPairCapacity)
        pair = togglePanel(pair, TimerTab, capacity = PortraitPairCapacity)
        assertEquals(listOf(HornTab, TimerTab), pair)
    }

    @Test
    fun switchingOffAtCapacityEvictsNothing() {
        // Removing must never trip the eviction path; only an addition can overflow.
        val pair = listOf(DjTab, MixTab)
        assertEquals(listOf(DjTab), togglePanel(pair, MixTab, capacity = PortraitPairCapacity))
    }

    @Test
    fun theDockIsUnboundedAndNeverEvicts() {
        // Default capacity is the large-screen dock's behaviour, which this refactor must keep.
        var dock = emptyList<DjRoute>()
        largeScreenPanels().forEach { dock = togglePanel(dock, it) }
        assertEquals(largeScreenPanels(), dock)
    }

    @Test
    fun aSelectionCanBeEmptied() {
        // Zero panels is a valid state: the visualization shows through where the pair was.
        assertEquals(emptyList(), togglePanel(listOf(DjTab), DjTab, capacity = PortraitPairCapacity))
    }

    @Test
    fun pulsarIsNeverOfferedToThePairBecausePortraitAlreadyShowsIt() {
        assertFalse(PulsarTab in portraitPairPanels())
    }

    @Test
    fun vibeInfoEarnsAPairSlotDespiteBeingASheetOnPhones() {
        assertTrue(VibeInfoTab.opensAsSheet)
        assertTrue(VibeInfoTab in portraitPairPanels())
    }

    @Test
    fun defaultPairIsDrawnFromTheEligiblePanels() {
        assertTrue(portraitPairPanels().containsAll(DefaultPortraitPair))
        assertEquals(PortraitPairCapacity, DefaultPortraitPair.size)
    }
}
