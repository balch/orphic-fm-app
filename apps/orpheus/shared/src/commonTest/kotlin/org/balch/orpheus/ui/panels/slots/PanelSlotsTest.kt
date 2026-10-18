package org.balch.orpheus.ui.panels.slots

import org.balch.orpheus.core.features.PanelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelSlotsTest {
    private val a = PanelId("a")
    private val b = PanelId("b")
    private val c = PanelId("c")
    private val d = PanelId("d")
    private val e = PanelId("e")
    private val x = PanelId("x")
    private val wide: (PanelId) -> Boolean = { it == PanelId.PULSAR }

    private fun slots(capacity: Int) = PanelSlots(capacity = capacity)

    private fun PanelSlots.openAll(ids: List<PanelId>) = ids.fold(this) { s, id -> s.open(id, wide) }

    private fun PanelSlots.unitsOf(id: PanelId) = open.first { it.id == id }.units

    @Test
    fun openAppendsUntilFull() {
        val s = slots(2).openAll(listOf(a, b))
        assertEquals(listOf(a, b), s.openIds)
        assertEquals(0, s.freeUnits)
    }

    @Test
    fun openOnAnOpenPanelOnlyTouchesIt() {
        val s = slots(3).openAll(listOf(a, b, c)).open(a, wide)
        assertEquals(listOf(a, b, c), s.openIds)
        assertEquals(listOf(a, c, b), s.lastUsed)
    }

    @Test
    fun openWhenFullReplacesLeastRecentlyUsedInPlace() {
        val s = slots(2).openAll(listOf(a, b)).touch(a).open(c, wide)
        assertEquals(listOf(a, c), s.openIds)
        assertEquals(listOf(b), s.recents)
    }

    @Test
    fun pinnedPanelIsNeverEvicted() {
        val s = slots(3).openAll(listOf(a, b, c)).togglePin(a).touch(b).touch(c).open(d, wide)
        assertEquals(listOf(a, d, c), s.openIds)
    }

    @Test
    fun wideTakesTwoUnitsOnlyFromThreeSlots() {
        val two = slots(2).open(PanelId.PULSAR, wide)
        assertEquals(1, two.unitsOf(PanelId.PULSAR))

        val three = slots(3).open(PanelId.PULSAR, wide)
        assertEquals(2, three.unitsOf(PanelId.PULSAR))
        assertEquals(1, three.freeUnits)
    }

    @Test
    fun wideNeverEvictsForItsSecondUnit() {
        val s = slots(3).openAll(listOf(a, b, c)).open(PanelId.PULSAR, wide)
        assertEquals(listOf(PanelId.PULSAR, b, c), s.openIds)
        assertEquals(1, s.unitsOf(PanelId.PULSAR))
    }

    @Test
    fun evictedWidePanelLeavesAFreeUnit() {
        val s = slots(3).openAll(listOf(PanelId.PULSAR, a)).touch(a).open(b, wide)
        assertEquals(listOf(b, a), s.openIds)
        assertEquals(1, s.unitsOf(b))
        assertEquals(1, s.freeUnits)
    }

    @Test
    fun wideTakesTheVictimsSecondUnit() {
        val bothWide: (PanelId) -> Boolean = { it == PanelId.PULSAR || it == PanelId.BEATS }
        val s = slots(3).open(PanelId.PULSAR, bothWide).open(a, bothWide).touch(a)
            .open(PanelId.BEATS, bothWide)
        assertEquals(listOf(PanelId.BEATS, a), s.openIds)
        assertEquals(2, s.unitsOf(PanelId.BEATS))
        assertEquals(0, s.freeUnits)
    }

    @Test
    fun canPinRespectsCap() {
        val s = slots(2).openAll(listOf(a, b)).togglePin(a)
        assertFalse(s.canPin(b))
        assertEquals(setOf(a), s.togglePin(b).pinned)
    }

    @Test
    fun pinnedPanelCanAlwaysBeUnpinned() {
        val s = slots(2).openAll(listOf(a, b)).togglePin(a)
        assertTrue(s.canPin(a))
        assertEquals(emptySet(), s.togglePin(a).pinned)
    }

    @Test
    fun singleSlotReplacesAndRefusesPins() {
        val s = slots(1).openAll(listOf(a, b))
        assertEquals(listOf(b), s.openIds)
        assertFalse(s.canPin(b))
    }

    @Test
    fun unknownCapacityRefusesPins() {
        val s = slots(0).openAll(listOf(a))
        assertFalse(s.canPin(a))
    }

    @Test
    fun closeUnpinsAndPushesToRecents() {
        val s = slots(3).openAll(listOf(a, b)).togglePin(a).close(a)
        assertFalse(a in s.pinned)
        assertEquals(a, s.recents.first())
        assertEquals(listOf(b), s.openIds)
        assertEquals(listOf(b), s.lastUsed)
    }

    @Test
    fun recentsAreCappedAtTwelve() {
        val ids = (0 until 14).map { PanelId("p$it") }
        val s = ids.fold(slots(2)) { acc, id -> acc.open(id, wide).close(id) }
        assertEquals(12, s.recents.size)
        assertEquals(ids.last(), s.recents.first())
    }

    @Test
    fun openRemovesFromRecents() {
        val s = slots(2).openAll(listOf(a)).close(a).open(a, wide)
        assertTrue(a !in s.recents)
    }

    @Test
    fun opensBeforeCapacityAreKeptThenTrimmed() {
        val s = slots(0).openAll(listOf(a, b, c, d))
        assertEquals(4, s.openIds.size)

        val trimmed = s.touch(b).withCapacity(2, wide)
        assertEquals(listOf(b, d), trimmed.openIds)
        assertEquals(c, trimmed.recents.first())
    }

    @Test
    fun shrinkUnpinsBeyondCapThenGrowRefills() {
        val s = slots(5).openAll(listOf(a, b, c, d, e)).togglePin(a).togglePin(b).togglePin(c)
        val small = s.withCapacity(2, wide)
        assertEquals(1, small.pinned.size)
        assertEquals(2, small.openIds.size)

        assertEquals(listOf(d, b, a), small.recents)

        val grown = small.withCapacity(5, wide)
        assertEquals(5, grown.openIds.size)
        assertEquals(listOf(c, e, d, b, a), grown.openIds)
    }

    @Test
    fun shrinkKeepsPinnedPanelsFirst() {
        val s = slots(4).openAll(listOf(a, b, c, d)).togglePin(a).withCapacity(2, wide)
        assertEquals(listOf(a, d), s.openIds)
        assertEquals(setOf(a), s.pinned)
    }

    @Test
    fun shrinkBelowThreeDropsWideToOneUnit() {
        val s = slots(4).openAll(listOf(PanelId.PULSAR, a)).withCapacity(2, wide)
        assertEquals(1, s.unitsOf(PanelId.PULSAR))
        assertEquals(listOf(PanelId.PULSAR, a), s.openIds)
    }

    @Test
    fun growUpgradesWideToTwoUnits() {
        val s = slots(2).openAll(listOf(PanelId.PULSAR)).withCapacity(3, wide)
        assertEquals(2, s.unitsOf(PanelId.PULSAR))
        assertEquals(1, s.freeUnits)
    }

    @Test
    fun applySetKeepsPinsAndFillsInOrder() {
        val s = slots(3).openAll(listOf(x)).togglePin(x).applySet(listOf(a, b, c), wide)
        assertEquals(listOf(x, a, b), s.openIds)
    }

    @Test
    fun applySetNeverEvictsAndClosesUnpinned() {
        val s = slots(2).openAll(listOf(a, b)).applySet(listOf(c), wide)
        assertEquals(listOf(c), s.openIds)
        assertTrue(a in s.recents && b in s.recents)
    }

    @Test
    fun closedPanelStaysClosedWhenShrinkingByOneUnit() {
        val s = slots(4).openAll(listOf(a, b, c)).close(c).withCapacity(3, wide)
        assertEquals(listOf(a, b), s.openIds)
        assertEquals(listOf(c), s.recents)
    }

    @Test
    fun shrinkSkipsAPanelThatDoesNotFitAndKeepsSlotOrder() {
        val s = slots(5).openAll(listOf(PanelId.PULSAR, a, b, c))
            .touch(c).touch(PanelId.PULSAR).touch(b).touch(a)
            .withCapacity(3, wide)
        // Recency is a, b, PULSAR, c. PULSAR (2 units) no longer fits after a and b, but c does.
        assertEquals(listOf(a, b, c), s.openIds)
        assertEquals(listOf(PanelId.PULSAR), s.recents)
    }

    @Test
    fun growFillsFromRecentsMostRecentFirst() {
        val s = slots(1).openAll(listOf(a, b, c, d)).withCapacity(3, wide)
        assertEquals(listOf(d, c, b), s.openIds)
        assertEquals(listOf(a), s.recents)
    }

    @Test
    fun firstCapacityWidensButNeverRefills() {
        val restored = PanelSlots(
            open = listOf(OpenPanel(PanelId.PULSAR, 1)),
            recents = listOf(a, b),
            lastUsed = listOf(PanelId.PULSAR),
        )
        val s = restored.withCapacity(4, wide)
        assertEquals(listOf(PanelId.PULSAR), s.openIds)
        assertEquals(2, s.unitsOf(PanelId.PULSAR))
        assertEquals(listOf(a, b), s.recents)
        assertEquals(2, s.freeUnits)

        // Growth from a known capacity still refills.
        assertEquals(listOf(PanelId.PULSAR, a, b), s.withCapacity(5, wide).openIds)
    }

    @Test
    fun growingWideFlankedByPinsKeepsAnUnpinnedUnit() {
        val bothWide: (PanelId) -> Boolean = { it == PanelId.PULSAR || it == PanelId.BEATS }
        val start = PanelSlots(
            open = listOf(OpenPanel(PanelId.PULSAR, 1), OpenPanel(PanelId.BEATS, 1)),
            pinned = setOf(PanelId.PULSAR, PanelId.BEATS),
            lastUsed = listOf(PanelId.PULSAR, PanelId.BEATS),
            capacity = 3,
        )
        val grown = start.withCapacity(4, bothWide)
        assertTrue(grown.open.filter { it.id in grown.pinned }.sumOf { it.units } <= 3)

        val opened = grown.open(a, bothWide)
        assertTrue(a in opened.openIds)
    }
}
