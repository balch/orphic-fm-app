package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelDockTest {

    @Test
    fun nothingDockedLeavesEveryRegionEmpty() {
        val dock = assignDock(emptyList())
        assertTrue(dock.isEmpty, "an empty rail must leave the visualizer alone")
        assertNull(dock.centre)
    }

    @Test
    fun pulsarTakesTheCentreStage() {
        val dock = assignDock(listOf(PulsarTab, DjTab))
        assertEquals(PulsarTab, dock.centre, "Pulsar is the centrepiece, not an edge panel")
        assertTrue(PulsarTab !in dock.left && PulsarTab !in dock.right, "and only the centre")
    }

    @Test
    fun pulsarCentresRegardlessOfToggleOrder() {
        // Switching Pulsar on last must not push it into an edge column.
        val dock = assignDock(listOf(DjTab, MixTab, PulsarTab))
        assertEquals(PulsarTab, dock.centre)
        assertEquals(listOf(DjTab), dock.left)
        assertEquals(listOf(MixTab), dock.right)
    }

    @Test
    fun centreIsEmptyWhenPulsarIsOff() {
        val dock = assignDock(listOf(DjTab, MixTab))
        assertNull(dock.centre, "with Pulsar off the visualization owns the middle")
    }

    @Test
    fun edgePanelsAlternateColumnsInToggleOrder() {
        val dock = assignDock(listOf(DjTab, MixTab, HornTab, TimerTab))
        assertEquals(listOf(DjTab, HornTab), dock.left, "first and third go left")
        assertEquals(listOf(MixTab, TimerTab), dock.right, "second and fourth go right")
    }

    @Test
    fun everyDockedPanelIsPlacedExactlyOnce() {
        val panels = largeScreenPanels()
        val dock = assignDock(panels)
        val placed = listOfNotNull(dock.centre) + dock.left + dock.right
        assertEquals(panels.size, placed.size, "no panel dropped or duplicated")
        assertEquals(panels.toSet(), placed.toSet())
    }

    @Test
    fun columnsStayBalanced() {
        for (count in 0..largeScreenPanels().size) {
            val dock = assignDock(largeScreenPanels().take(count))
            val skew = dock.left.size - dock.right.size
            assertTrue(skew in 0..1, "count=$count: columns drifted out of balance by $skew")
        }
    }

    @Test
    fun addingAPanelNeverMovesTheOnesAlreadyPlaced() {
        // The rail is driven by a remote, so growth must be predictable.
        val order = listOf(DjTab, MixTab, HornTab, TimerTab)
        for (count in 1 until order.size) {
            val before = assignDock(order.take(count))
            val after = assignDock(order.take(count + 1))
            assertEquals(
                before.left, after.left.take(before.left.size),
                "left column reshuffled when a panel was added at count=$count",
            )
            assertEquals(
                before.right, after.right.take(before.right.size),
                "right column reshuffled when a panel was added at count=$count",
            )
        }
    }
}
