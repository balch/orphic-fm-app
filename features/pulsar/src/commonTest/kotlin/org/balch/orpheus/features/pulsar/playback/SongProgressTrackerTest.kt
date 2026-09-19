package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongProgressTrackerTest {
    private var clock = 0L
    private val tracker = SongProgressTracker { clock }

    // 32 steps at 120 BPM: 32 x 15000 / 120 = 4000ms a loop-cycle. Estimate 150 + 90 * 3 / 4 = 217s, forced end 240s.
    private val verse = SongTiming("Dog House", stepCount = 32, bpm = 120f, bpmMultiplier = 1f, songSeconds = 150..240, finalSectionIndex = -1)
    private val doubleTime = verse.copy(bpmMultiplier = 2f)
    private val estimate = 217_000L

    private fun state(section: Int, bars: Int, total: Int = 8) =
        PulsarArrangementState(section, bars, total, false, -1, 0)

    // Plays [bars] loop-cycle boundaries of [gapMs] each, from bar [from] of the section, and returns the last progress.
    private fun play(section: Int, bars: Int, gapMs: Long = 4_000, timing: SongTiming = verse, total: Int = 8, from: Int = 0): PlaybackProgress? {
        var progress: PlaybackProgress? = null
        for (bar in from + 1..from + bars) {
            clock += gapMs
            progress = tracker.update(state(section, bar, total), timing)
        }
        return progress
    }

    @Test
    fun theSeedDrivesASectionsFirstCycles() {
        assertEquals(PlaybackProgress(0, estimate), tracker.update(state(0, 0), verse))
        assertEquals(PlaybackProgress(4_000, estimate), play(0, bars = 1, gapMs = 8_000))
    }

    @Test
    fun twoSamplesDoNotOverruleTheSeed() {
        tracker.update(state(0, 0), verse)
        assertEquals(PlaybackProgress(8_000, estimate), play(0, bars = 2, gapMs = 8_000))
    }

    @Test
    fun threeSamplesReplaceAWrongSeed() {
        // The loop really runs 8000ms, twice the seed; the median takes over at three samples.
        tracker.update(state(0, 0), verse)
        assertEquals(PlaybackProgress(24_000, estimate), play(0, bars = 3, gapMs = 8_000))
    }

    @Test
    fun aPauseIsOneOutlierAndDoesNotMoveTheMedian() {
        tracker.update(state(0, 0), verse)
        listOf(4_000L, 4_000L, 4_000L, 60_000L, 4_000L).forEachIndexed { i, gap ->
            clock += gap
            tracker.update(state(0, i + 1), verse)
        }
        assertEquals(PlaybackProgress(20_000, estimate), tracker.update(state(0, 5), verse))
    }

    @Test
    fun sectionsAccumulateIntoTheSongPosition() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 8)
        // Eight 4000ms cycles are behind us when the second section starts.
        clock += 4_000
        assertEquals(PlaybackProgress(32_000, estimate), tracker.update(state(1, 0), verse))
        assertEquals(PlaybackProgress(44_000, estimate), play(1, bars = 3))
    }

    @Test
    fun aSectionTempoChangeRescalesTheMeasurement() {
        // Measured 8000ms at 1x over 8 bars; the double-time section gets 4000ms a cycle, not its 2000ms seed.
        tracker.update(state(0, 0), verse)
        play(0, bars = 8, gapMs = 8_000)
        clock += 8_000
        tracker.update(state(1, 0), doubleTime)
        assertEquals(PlaybackProgress(64_000 + 8_000, estimate), play(1, bars = 2, gapMs = 4_000, timing = doubleTime))
    }

    @Test
    fun aTerminalSectionLoopingCountsAsAnotherPass() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 8)
        clock += 4_000
        // barsElapsed drops back to 0 in the same section: the engine re-entered it.
        assertEquals(PlaybackProgress(32_000, estimate), tracker.update(state(0, 0), verse))
    }

    @Test
    fun aNewVibeStartsFromItsOwnSeedAtZero() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 8, gapMs = 8_000)
        val next = SongTiming("Rust Belt", stepCount = 16, bpm = 60f, bpmMultiplier = 1f, songSeconds = 180..200, finalSectionIndex = -1)
        // 16 x 15000 / 60 = 4000ms, the new vibe's seed, not the old measurement; estimate 180 + 20 * 3 / 4 = 195s.
        assertEquals(PlaybackProgress(0, 195_000), tracker.update(state(0, 0), next))
        assertEquals(PlaybackProgress(4_000, 195_000), play(0, bars = 1, gapMs = 8_000, timing = next))
    }

    @Test
    fun theOldSongsStateLaggingAVibeChangeIsNotSongTime() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 8)
        val next = verse.copy(vibeName = "Rust Belt")
        // The engine still reports the old song's section for a poll after the vibe changes.
        tracker.update(state(2, 5), next)
        clock += 200
        assertEquals(PlaybackProgress(0, estimate), tracker.update(state(0, 0), next))
        assertEquals(PlaybackProgress(8_000, estimate), play(0, bars = 2, timing = next))
    }

    @Test
    fun noArrangementMeansNoSeekBar() {
        assertNull(tracker.update(ARRANGEMENT_STATE_UNKNOWN, verse))
        assertNull(tracker.update(state(0, 0, total = 0), verse))
        assertNull(tracker.update(state(0, 0), null))
    }

    @Test
    fun theFinalSectionSetsTheExactEnd() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 8)
        clock += 4_000
        // The ending is armed and the outro (section 3, 6 bars) begins: the song ends when it does.
        val ending = verse.copy(finalSectionIndex = 3)
        assertEquals(PlaybackProgress(32_000, 56_000), tracker.update(state(3, 0, total = 6), ending))
        assertEquals(PlaybackProgress(44_000, 56_000), play(3, bars = 3, timing = ending, total = 6))
    }

    @Test
    fun armingInsideTheFinalSectionEndsAtItsBoundary() {
        tracker.update(state(0, 0), verse)
        play(0, bars = 3)
        // Armed while already in the outro: the engine finishes this pass, then re-enters and ends.
        assertEquals(PlaybackProgress(12_000, 32_000), tracker.update(state(0, 3), verse.copy(finalSectionIndex = 0)))
    }

    @Test
    fun overrunningTheEstimateBumpsToTheForcedEndThenRollsBySection() {
        tracker.update(state(0, 0, total = 60), verse)
        // 55 cycles = 220s, past the 217s estimate: the duration moves to the forced 240s end.
        assertEquals(PlaybackProgress(216_000, estimate), play(0, bars = 54, total = 60))
        assertEquals(PlaybackProgress(220_000, 240_000), play(0, bars = 1, total = 60, from = 54))
        // Past the forced end (song ending off), the end rolls to the current section's boundary.
        clock += 4_000
        tracker.update(state(1, 0, total = 50), verse)
        assertEquals(PlaybackProgress(280_000, 440_000), play(1, bars = 10, total = 50))
    }

    // Records one loop-cycle boundary [gapMs] after the last and returns the published duration.
    private fun boundaryAfter(gapMs: Long, bar: Int, total: Int, timing: SongTiming): Long? {
        clock += gapMs
        return tracker.update(state(3, bar, total), timing)?.durationMs
    }

    @Test
    fun theFinalSectionsEndHoldsSteadyThroughPollJitter() {
        // The state is polled every 200ms, so a steady 4000ms cycle lands 19 or 20 polls apart.
        val pollMs = 200L
        val ending = verse.copy(finalSectionIndex = 3)
        tracker.update(state(3, 0, total = 32), ending)
        val durations = (1..16).map { bar ->
            boundaryAfter(gapMs = pollMs * (if (bar % 2 == 1) 19 else 20), bar = bar, total = 32, timing = ending)
        }
        val measured = durations.drop(MinCycleSamples - 1)
        assertEquals(List(measured.size) { measured.first() }, measured)
    }

    @Test
    fun aRealTempoChangeStillMovesTheFinalSectionsEnd() {
        val ending = verse.copy(finalSectionIndex = 3)
        tracker.update(state(3, 0, total = 32), ending)
        repeat(CycleWindow) { i -> boundaryAfter(gapMs = 4_000, bar = i + 1, total = 32, timing = ending) }
        assertEquals(128_000, boundaryAfter(gapMs = 4_000, bar = CycleWindow + 1, total = 32, timing = ending))

        // The loop now runs twice as fast: 2000ms a cycle, 64000ms for 32 cycles.
        val durations = (1..MinCycleSamples + 4).map { i ->
            boundaryAfter(gapMs = 2_000, bar = CycleWindow + 1 + i, total = 32, timing = ending)
        }
        val moved = durations.indexOfFirst { it == 64_000L }
        assertTrue(moved in 0 until MinCycleSamples, "duration still $durations")
        assertEquals(List(moved) { 128_000L }, durations.take(moved))
        assertEquals(List(durations.size - moved) { 64_000L }, durations.drop(moved))
    }

    @Test
    fun theSeedIsALoopCycleNotAFourFourBar() {
        assertEquals(4_000f, seedMsPerCycle(32, 120f, 1f))
        assertEquals(2_000f, seedMsPerCycle(16, 120f, 1f))
        assertNull(seedMsPerCycle(32, 0f, 1f))
        assertNull(seedMsPerCycle(0, 120f, 1f))
    }
}
