package org.balch.orpheus.features.pulsar.vibes.classical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Authoring guard for the Fifth as a Score. `VibeCatalogScan` only walks the top-level vibes
 * package, so nothing else constructs this vibe: building it here is what runs `Vibe.init`
 * and `Arrangement.init` over it.
 */
class FifthSymphonyVibeTest {
    private val vibe = FifthSymphonyVibe().vibe

    @Test
    fun everySectionPinsAThemeInsideThePool() {
        val pool = assertNotNull(vibe.lickRotation).pool
        val sections = assertNotNull(vibe.arrangement).sections
        sections.forEach { section ->
            val index = assertNotNull(section.lickIndex, "${section.name} must pin a lick")
            assertTrue(index in pool.indices, "${section.name} pins slot $index outside the pool")
        }
        // Every theme is heard: no slot is authored and then never pinned.
        assertEquals(pool.indices.toSet(), sections.mapNotNull { it.lickIndex }.toSet())
    }

    @Test
    fun everyLickFitsTheFillWindow() {
        // The lead is LickMode.Fill over stepCount steps at 4 steps/beat.
        val windowBeats = vibe.stepCount / 4f
        val licks = assertNotNull(vibe.lickRotation).pool + listOfNotNull(vibe.bassLine)
        licks.forEachIndexed { i, lick ->
            val beats = lick.steps.sumOf { it.duration.toDouble() }.toFloat()
            assertTrue(beats <= windowBeats, "lick $i runs $beats beats, window is $windowBeats")
            assertEquals(windowBeats.toInt(), lick.loopLength, "lick $i loopLength")
        }
    }

    @Test
    fun everySectionIsAFixedLength() {
        assertNotNull(vibe.arrangement).sections.forEach {
            assertEquals(it.barsMin, it.barsMax, "${it.name} must be a fixed length")
        }
    }
}
