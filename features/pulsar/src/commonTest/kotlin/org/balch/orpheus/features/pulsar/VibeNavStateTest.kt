package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeNavStateTest {
    private val names = listOf("A", "B", "C")

    @Test
    fun namesBothNeighbours() {
        val s = vibeNavStateOf(names, "B", null)
        assertEquals("A", s.previousName)
        assertEquals("C", s.nextName)
        assertNull(s.progress)
        assertFalse(s.previousRestarts)
    }

    @Test
    fun progressIsAFractionClampedToOne() {
        assertEquals(0.25f, vibeNavStateOf(names, "A", PlaybackProgress(50_000, 200_000)).progress)
        assertEquals(1f, vibeNavStateOf(names, "A", PlaybackProgress(300_000, 200_000)).progress)
    }

    @Test
    fun aZeroDurationHasNoProgress() = assertNull(vibeNavStateOf(names, "A", PlaybackProgress(0, 0)).progress)

    // The chrome runs the playhead on between the tracker's loop-cycle updates, so it needs the times too.
    @Test
    fun carriesThePositionAndDuration() {
        val s = vibeNavStateOf(names, "A", PlaybackProgress(50_000, 200_000))
        assertEquals(50_000L, s.positionMs)
        assertEquals(200_000L, s.durationMs)
    }

    @Test
    fun noProgressNoTimes() {
        listOf(null, PlaybackProgress(0, 0)).forEach {
            val s = vibeNavStateOf(names, "A", it)
            assertNull(s.positionMs)
            assertNull(s.durationMs)
        }
    }

    @Test
    fun farIntoTheSongPreviousRestarts() = assertTrue(vibeNavStateOf(names, "A", PlaybackProgress(60_000, 200_000)).previousRestarts)

    @Test
    fun anAiVibeNamesTheCatalogEnds() {
        val s = vibeNavStateOf(names, "AI Vibe", null)
        assertEquals("C", s.previousName)
        assertEquals("A", s.nextName)
    }
}
