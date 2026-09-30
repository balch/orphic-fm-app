package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.features.pulsar.playback.VibeRotation
import org.balch.orpheus.features.pulsar.playback.songArc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeNavStateTest {
    private val names = VibeRotation.of(listOf("A", "B", "C"))

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

    // Every surface draws the same arc, TV and a ring's first frame included: slowed until the end is
    // known, then bending from where it locked in.
    @Test
    fun progressIsTheSongArc() {
        val estimated = PlaybackProgress(230_000, 240_000, durationFinal = false, estimateMs = 217_000)
        assertEquals(songArc(230_000f, 240_000, false, 217_000, null), vibeNavStateOf(names, "A", estimated).progress)
        val locked = PlaybackProgress(250_000, 280_000, estimateMs = 217_000, lockedAtMs = 240_000)
        assertEquals(songArc(250_000f, 280_000, true, 217_000, 240_000), vibeNavStateOf(names, "A", locked).progress)
        assertEquals(240_000L, vibeNavStateOf(names, "A", locked).lockedAtMs)
    }

    @Test
    fun aZeroDurationHasNoProgress()= assertNull(vibeNavStateOf(names, "A", PlaybackProgress(0, 0)).progress)

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

    @Test
    fun aSetAsideVibeNamesItsSlotsNeighbours() {
        val s = vibeNavStateOf(VibeRotation(listOf("A", "B", "C", "D"), listOf("A", "D")), "B", null)
        assertEquals("A", s.previousName)
        assertEquals("D", s.nextName)
    }
}
