package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.LpgMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluckTest {
    @Test fun skankIsEighthOffbeats() {
        val p = pluckOf(findTabSystems(TabParserFixtures.SKANK))!!
        assertEquals(PluckGrid.EIGHTH_OFFBEATS, p.grid); assertEquals(LpgMode.PLUCK_REPEAT_8TH_OFF, p.lpgMode)
        assertEquals(listOf(0.5f, 1.5f, 2.5f, 3.5f), p.onsets); assertEquals(4f, p.picksPerBar)
        assertEquals(RhythmSource.BARS, p.rhythm)
    }

    @Test fun upticksAreSixteenthOffbeats() =
        assertEquals(LpgMode.PLUCK_REPEAT_16TH_OFF, pluckOf(findTabSystems(TabParserFixtures.UPTICKS))!!.lpgMode)

    @Test fun tripletsAreTriplets() {
        val p = pluckOf(findTabSystems(TabParserFixtures.TRIPLETS))!!
        assertEquals(PluckGrid.TRIPLETS, p.grid); assertEquals(LpgMode.PLUCK_REPEAT_TRIPLET, p.lpgMode); assertEquals(12f, p.picksPerBar)
    }

    @Test fun twoAndFourIsDownbeats() {
        val p = pluckOf(findTabSystems(TabParserFixtures.TWO_AND_FOUR))!!
        assertEquals(PluckGrid.DOWNBEATS, p.grid); assertEquals(LpgMode.PLUCK, p.lpgMode); assertEquals(listOf(1f, 3f), p.onsets)
    }

    @Test fun noBarsCountsFromTheFirstStroke() {
        val p = pluckOf(findTabSystems(TabParserFixtures.SKANK_NO_BARS))!!
        assertEquals(RhythmSource.SPACING, p.rhythm); assertEquals(PluckGrid.EIGHTHS, p.grid)
        assertEquals(8f, p.picksPerBar)
    }

    @Test fun handSpacedMixedSubdivisionsAreMixedPluck() {
        // Strokes at columns 2, 4, 8, 13, 15, 19, 24: gaps 2, 4, 5, 2, 4, 5.
        val rows = listOf("e--0-0---0----0-0---0----0----|", "B--1-1---1----1-1---1----1----|", "G--2-2---2----2-2---2----2----|",
            "D--2-2---2----2-2---2----2----|", "A--0-0---0----0-0---0----0----|", "E-----------------------------|")
        val p = pluckOf(findTabSystems(rows))!!
        assertEquals(RhythmSource.SPACING, p.rhythm); assertEquals(PluckGrid.MIXED, p.grid); assertEquals(LpgMode.PLUCK, p.lpgMode)
    }

    @Test fun barredSixteenthsStillRepick() {
        val rows = listOf("e|-5555555555555555|-5555555555555555|", "B|-5555555555555555|-5555555555555555|", "G|-5555555555555555|-5555555555555555|",
            "D|-5555555555555555|-5555555555555555|", "A|-----------------|-----------------|", "E|-----------------|-----------------|")
        val p = pluckOf(findTabSystems(rows))!!
        assertEquals(RhythmSource.BARS, p.rhythm); assertEquals(PluckGrid.SIXTEENTHS, p.grid); assertEquals(LpgMode.PLUCK_REPEAT, p.lpgMode)
    }

    @Test fun barsWithoutAPadColumnStillReadAsBeats() {
        val p = pluckOf(findTabSystems(TabParserFixtures.NO_PAD_STRUMS))!!
        assertEquals(RhythmSource.BARS, p.rhythm); assertEquals(PluckGrid.DOWNBEATS, p.grid); assertEquals(listOf(0f, 1f, 2f, 3f), p.onsets)
        val late = pluckOf(findTabSystems(TabParserFixtures.LATE_STRUMS))!!
        assertEquals(PluckGrid.EIGHTH_OFFBEATS, late.grid); assertEquals(listOf(0.5f, 1.5f, 2.5f, 3.5f), late.onsets)
    }

    @Test fun offGridBarWidthsStillSpanFourBeats() {
        val strums = pluckOf(findTabSystems(TabParserFixtures.WIDE_STRUMS))!!
        assertEquals(PluckGrid.DOWNBEATS, strums.grid); assertEquals(listOf(0f, 1f, 2f, 3f), strums.onsets)
        val skank = pluckOf(findTabSystems(TabParserFixtures.WIDE_SKANK))!!
        assertEquals(PluckGrid.EIGHTH_OFFBEATS, skank.grid); assertEquals(LpgMode.PLUCK_REPEAT_8TH_OFF, skank.lpgMode)
        assertEquals(listOf(0.5f, 1.5f, 2.5f, 3.5f), skank.onsets); assertEquals(4f, skank.picksPerBar)
    }

    @Test fun barsAFewColumnsOff16StayOnTheBeat() {
        listOf(TabParserFixtures.STRUMS_18, TabParserFixtures.STRUMS_19, TabParserFixtures.STRUMS_15).forEachIndexed { k, rows ->
            val p = pluckOf(findTabSystems(rows))!!
            assertEquals(PluckGrid.DOWNBEATS, p.grid, "fixture $k"); assertEquals(listOf(0f, 1f, 2f, 3f), p.onsets, "fixture $k")
        }
    }

    @Test fun noOnsetsIsNull()= assertNull(pluckOf(emptyList()))
}
