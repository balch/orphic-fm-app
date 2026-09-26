package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EndlessGapTest {
    private fun sparksAt(playMs: Long): List<Pair<Float, Float>> =
        buildList { forEachGapSpark(playMs) { along, brightness -> add(along to brightness) } }

    // Clear of the playhead's cap at one end and of 12 o'clock (or the band's edge) at the other.
    @Test
    fun everySparkStaysInsideTheGapAndInRange() {
        (0L..60_000L step 37L).forEach { ms ->
            val sparks = sparksAt(ms)
            assertEquals(GapSparks, sparks.size)
            sparks.forEach { (along, brightness) ->
                assertTrue(along in SparkMargin..1f - SparkMargin, "at $ms a spark sat at $along")
                assertTrue(brightness in 0f..1f, "at $ms a spark shone $brightness")
            }
        }
    }

    // A glint is dark as its twinkle starts and ends, so moving it to a new spot between twinkles never shows.
    @Test
    fun aTwinkleStartsAndEndsDark() {
        assertEquals(0f, sparkTwinkle(0f), 1e-3f)
        assertEquals(0f, sparkTwinkle(1f), 1e-3f)
        assertTrue((1..99).map { sparkTwinkle(it / 100f) }.max() > 0.9f, "it never flashed")
    }

    @Test
    fun aSparkMovesToANewSpotEachTwinkle() {
        val first = sparksAt(0L).first().first
        val next = sparksAt(SparkTwinkleMs).first().first
        assertNotEquals(first, next)
        // The same moment always draws the same sparks: a paused frame holds still.
        assertEquals(sparksAt(12_345L), sparksAt(12_345L))
    }

    // Staggered: never all dark at once, so the gap always has something alight.
    @Test
    fun theSparksNeverAllGoDarkTogether() {
        (0L..10_000L step 16L).forEach { ms ->
            assertTrue(sparksAt(ms).maxOf { it.second } > 0.2f, "all dark at $ms")
        }
    }

    @Test
    fun theGlowBreathesButNeverGoesOut() {
        val breaths = (0L..4_000L step 16L).map(::gapBreath)
        assertTrue(breaths.min() >= 0.5f && breaths.max() <= 1f, "breath ${breaths.min()}..${breaths.max()}")
        assertTrue(breaths.max() - breaths.min() > 0.2f, "it never breathed")
    }
}
