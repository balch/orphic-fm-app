package org.balch.orpheus.features.dj

import org.balch.orpheus.core.plugin.symbols.DjDrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjViewModelDropTest {

    @Test
    fun defaultDecksHaveTwoEntriesAllNone() {
        val s = DjUiState()
        assertEquals(2, s.decks.size)
        assertTrue(s.decks.all { !it.dragActive && it.drop == DjDrop.NONE })
    }

    @Test
    fun defaultZoneOrderContainsFourDropsExcludingNone() {
        val s = DjUiState()
        val set = s.zoneOrder.toSet()
        assertEquals(4, s.zoneOrder.size)
        assertTrue(DjDrop.FILTER in set)
        assertTrue(DjDrop.BRAKE in set)
        assertTrue(DjDrop.STUTTER in set)
        assertTrue(DjDrop.FREEZE in set)
        assertTrue(DjDrop.NONE !in set)
    }

    @Test
    fun pickZoneOrderAlwaysReturnsFourUniqueNonNoneDrops() {
        repeat(1000) {
            val picked = pickZoneOrder()
            assertEquals(4, picked.size, "expected exactly 4 drops")
            assertEquals(4, picked.toSet().size, "expected no duplicates, got $picked")
            assertTrue(DjDrop.NONE !in picked, "NONE must never appear, got $picked")
        }
    }

    @Test
    fun pickZoneOrderWeightingFavorsHighWeightDrops() {
        // Over many draws, weight-3 drops should appear ~3x as often as weight-1 drops
        // (BRAKE and FREEZE are weight 1; FILTER, STUTTER, OCTAVE, PHASER, ECHO, RING are weight 3).
        val counts = DjDrop.entries.associateWith { 0 }.toMutableMap()
        val runs = 5_000
        repeat(runs) {
            pickZoneOrder().forEach { counts[it] = counts.getValue(it) + 1 }
        }
        val weight1Avg = (counts.getValue(DjDrop.BRAKE) + counts.getValue(DjDrop.FREEZE)) / 2.0
        val weight3Drops = listOf(DjDrop.FILTER, DjDrop.STUTTER, DjDrop.OCTAVE, DjDrop.PHASER, DjDrop.ECHO, DjDrop.RING)
        val weight3Avg = weight3Drops.sumOf { counts.getValue(it) } / weight3Drops.size.toDouble()
        // Weight-3 should be meaningfully more frequent than weight-1.
        // Ratio is ~3x in the idealized model but pickZoneOrder removes picked drops from
        // the pool so the actual ratio is diluted. We just assert a conservative 1.5x here.
        assertTrue(
            weight3Avg > weight1Avg * 1.5,
            "weight-3 average ($weight3Avg) should exceed weight-1 average ($weight1Avg) * 1.5"
        )
    }

    @Test
    fun pickZoneOrderCoversAllWeightedDropsOverManyRuns() {
        // Every weighted drop should appear at least once over 1000 independent shuffles.
        val seen = mutableSetOf<DjDrop>()
        repeat(1000) { seen.addAll(pickZoneOrder()) }
        val expected = DjDrop.entries.filter { it.weight > 0 }.toSet()
        assertEquals(expected, seen, "every weighted drop should have appeared: missing ${expected - seen}")
    }

    @Test
    fun deckDropStateCopySemanticsAreIsolated() {
        // Modifying one deck via copy must not affect the other — sanity for the reducer's
        // mapIndexed pattern in DjViewModel.reduce(SetDrop/SetDragActive).
        val s = DjUiState()
        val after = s.copy(decks = s.decks.mapIndexed { i, d -> if (i == 0) d.copy(drop = DjDrop.PHASER) else d })
        assertEquals(DjDrop.PHASER, after.decks[0].drop)
        assertEquals(DjDrop.NONE, after.decks[1].drop)
    }
}
