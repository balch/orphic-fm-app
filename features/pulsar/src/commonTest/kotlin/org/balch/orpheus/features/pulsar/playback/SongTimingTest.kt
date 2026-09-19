package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongTimingTest {
    private val arrangement = Arrangement(
        sections = listOf(Section(name = "Verse"), Section(name = "Breakdown", bpmMultiplier = 0.5f)),
        lengthSeconds = 150..240,
    )

    private fun timingOf(arr: Arrangement?, sectionIndex: Int, finalSectionIndex: Int = -1) =
        songTimingOf("Dog House", stepCount = 32, bpm = 120f, arrangement = arr, sectionIndex = sectionIndex, finalSectionIndex = finalSectionIndex)

    @Test
    fun aVibeWithoutAnArrangementHasNoSongTiming() {
        // The session keeps the last arrangement state, so a stale non-negative index arrives here.
        assertNull(timingOf(null, sectionIndex = 1))
        assertNull(timingOf(Arrangement(sections = emptyList(), introIndex = null), sectionIndex = 0))
    }

    @Test
    fun theSectionSetsTheMultiplier() {
        assertEquals(
            SongTiming("Dog House", stepCount = 32, bpm = 120f, bpmMultiplier = 0.5f, songSeconds = 150..240, finalSectionIndex = -1),
            timingOf(arrangement, sectionIndex = 1),
        )
        assertEquals(1f, timingOf(arrangement, sectionIndex = 0)?.bpmMultiplier)
    }

    @Test
    fun anIndexPastTheEndRunsAtTheVibesTempo() {
        assertEquals(1f, timingOf(arrangement, sectionIndex = 5)?.bpmMultiplier)
    }

    @Test
    fun theFinalSectionPassesThrough() {
        assertEquals(1, timingOf(arrangement, sectionIndex = 0, finalSectionIndex = 1)?.finalSectionIndex)
    }

    @Test
    fun aPlayOncePassIsItsOwnLengthNotTheAuthoredRange() {
        // A cycle is 32 * 15000 / 120 = 4 s. Intro 2..4 cycles = 8..16 s; the half-time outro is
        // 2 cycles of 8 s = 16 s. The pass runs 24..32 s, whatever lengthSeconds says.
        val once = Arrangement(
            sections = listOf(
                Section(name = "Intro", barsMin = 2, barsMax = 4),
                Section(name = "Outro", barsMin = 2, barsMax = 2, bpmMultiplier = 0.5f),
            ),
            introIndex = 0, outroIndex = 1, playOnce = true,
            lengthSeconds = 150..240,
        )
        assertEquals(24..32, timingOf(once, sectionIndex = 0)?.songSeconds)
    }

    @Test
    fun theEstimateSitsThreeQuartersIntoTheAuthoredRange() {
        // 150 + 90 * 3 / 4 = 217s.
        val timing = timingOf(arrangement, sectionIndex = 0)!!
        assertEquals(217_000L, timing.estimatedMs)
        assertEquals(240_000L, timing.maxMs)
    }
}
