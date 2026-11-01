package org.balch.orpheus.djapp

import androidx.compose.ui.unit.dp
import org.balch.orpheus.djapp.variant.DjEdition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the AI edition's bottom bar shows on each iPad, full screen, in points. iOS draws at its own
 * density (no canvas scale), so the dock counts its slots at the window's width as given.
 */
class IpadNavBarTest {
    private val order = defaultNavOrder(navPanels(DjEdition.AI))

    private class Seen(val layout: DjLayout, val shown: List<String>, val offBar: List<String>)

    /** The layout the window resolves to, and the bar that layout builds, as the app does. */
    private fun bar(width: Int, height: Int): Seen {
        val layout = resolveLayout(width.dp, height.dp)
        val slots = if (layout == DjLayout.LargeScreen) tvBarMaxSlots(width.dp) else maxEvenSlots(width.dp)
        val bar = navBar(order, navDrops(layout), slots)
        val shown = (bar.left + bar.right).filterIsInstance<NavEntry.Panel>().map { it.route.label }
        return Seen(layout, shown, bar.offBar.map { it.label })
    }

    private val dockSix = listOf("DJ", "Mix", "Pulsar", "AI", "Horn", "Cowbell")
    private val phoneSix = listOf("DJ", "Mix", "AI", "Timer", "Horn", "Cowbell")

    private fun assertDock(width: Int, height: Int, shown: List<String>, offBar: List<String> = emptyList()) {
        val seen = bar(width, height)
        assertEquals(DjLayout.LargeScreen, seen.layout, "${width}x$height")
        assertEquals(shown, seen.shown, "${width}x$height")
        assertEquals(offBar, seen.offBar, "${width}x$height")
    }

    private fun assertPair(width: Int, height: Int) {
        val seen = bar(width, height)
        assertEquals(DjLayout.PortraitPair, seen.layout, "${width}x$height")
        assertEquals(phoneSix, seen.shown, "${width}x$height")
        assertEquals(emptyList(), seen.offBar, "${width}x$height")
    }

    @Test
    fun `every iPad sideways docks with six`() {
        assertDock(1133, 744, dockSix) // mini
        assertDock(1180, 820, dockSix) // iPad, Air 11"
        assertDock(1210, 834, dockSix) // Pro 11"
        assertDock(1366, 1024, dockSix) // Air 13"
        assertDock(1376, 1032, dockSix) // Pro 13"
    }

    @Test
    fun `the 11 inch and smaller iPads upright keep the phone bar with six`() {
        assertPair(744, 1133) // mini
        assertPair(820, 1180) // iPad, Air 11"
        assertPair(834, 1210) // Pro 11"
    }

    @Test
    fun `the 13 inch iPads upright dock with four`() {
        val off = listOf("Horn", "Cowbell")
        assertDock(1024, 1366, listOf("DJ", "Mix", "Pulsar", "AI"), off) // Air 13"
        assertDock(1032, 1376, listOf("DJ", "Mix", "Pulsar", "AI"), off) // Pro 13"
    }
}
