package org.balch.orpheus.djapp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The clock here is the play clock: it runs only while playing (see ProgressWave.playMs). */
class SmoothProgressTest {
    private fun at(positionMs: Long, durationMs: Long = 120_000L, song: String = "Rust Belt") =
        SongPosition(song, positionMs, durationMs)

    @Test
    fun itAdvancesLinearlyWithPlayTime() {
        val s = SmoothPosition.start(at(30_000L), clockMs = 5_000L)
        assertEquals(30_000f, s.positionAt(5_000L))
        assertEquals(31_000f, s.positionAt(6_000L))
        assertEquals(0.3f, s.fractionAt(11_000L), 1e-6f)
    }

    // Paused, the play clock stands still, and an update landing mid-pause waits for play to move it.
    @Test
    fun itIsFrozenWhilePaused() {
        val s = SmoothPosition.start(at(30_000L), clockMs = 5_000L)
        val paused = s.positionAt(6_000L)
        val landed = s.next(at(31_200L), clockMs = 6_000L)
        assertEquals(paused, landed.positionAt(6_000L))
        assertTrue(landed.positionAt(6_100L) > paused, "play resumed and it never moved")
    }

    @Test
    fun itIsClampedAtTheEnd() {
        val s = SmoothPosition.start(at(119_000L), clockMs = 0L)
        assertEquals(1f, s.fractionAt(5_000L))
        assertEquals(120_000f, s.positionAt(60_000L))
    }

    // The display ran 200 ms past a late update: it holds there until the real position catches up.
    @Test
    fun anUpdateJustBehindNeverMovesItBackwards() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L).next(at(4_000L), clockMs = 4_200L)
        assertEquals(4_200f, s.positionAt(4_200L))
        assertEquals(4_200f, s.positionAt(4_300L))
        assertEquals(4_200f, s.positionAt(4_400L))
        assertEquals(4_300f, s.positionAt(4_500L))
    }

    // Ahead by less than a cycle: eased up to over a few frames, never jumped, never overshot.
    @Test
    fun anUpdateJustAheadIsEasedUpTo() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L).next(at(4_300L), clockMs = 4_000L)
        assertEquals(4_000f, s.positionAt(4_000L))
        val frames = (4_000L..5_000L step 16).map { s.positionAt(it) to 4_300f + (it - 4_000L) }
        frames.zipWithNext().forEach { (a, b) -> assertTrue(b.first >= a.first, "it went back: ${a.first} -> ${b.first}") }
        frames.forEach { (shown, real) -> assertTrue(shown <= real, "it overshot: $shown past $real") }
        assertTrue(frames[1].first - frames[0].first < 100f, "one frame jumped ${frames[1].first - frames[0].first} ms")
        assertEquals(5_300f, s.positionAt(5_000L), 1f)
    }

    @Test
    fun anUpdateFarAheadJumpsToIt() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L).next(at(40_000L), clockMs = 4_000L)
        assertEquals(40_000f, s.positionAt(4_000L))
    }

    // Behind by more than the loop-cycle last measured between updates: a seek back.
    @Test
    fun anUpdateFarBehindJumpsBack() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L)
            .next(at(2_000L), clockMs = 2_000L)
            .next(at(20_000L), clockMs = 2_016L)
            .next(at(15_000L), clockMs = 2_032L)
        assertEquals(15_000f, s.positionAt(2_032L))
    }

    @Test
    fun aNewSongJumpsToIt() {
        val s = SmoothPosition.start(at(60_000L), clockMs = 0L).next(at(0L, 90_000L, song = "Dog House"), clockMs = 1_000L)
        assertEquals(0f, s.positionAt(1_000L))
        assertEquals(90_000L, s.durationMs)
    }

    // A restart keeps the name, but its progress starts over near 0.
    @Test
    fun aRestartJumpsBackToTheStart() {
        val s = SmoothPosition.start(at(60_000L), clockMs = 0L).next(at(0L), clockMs = 1_000L)
        assertEquals(0f, s.positionAt(1_000L))
    }

    // The final section's real end replaces the estimate: the position runs on, the fraction rescales.
    @Test
    fun aNewDurationRescalesTheFractionOnly() {
        val s = SmoothPosition.start(at(60_000L), clockMs = 0L).next(at(62_000L, durationMs = 80_000L), clockMs = 2_000L)
        assertEquals(62_000f, s.positionAt(2_000L))
        assertEquals(62_000f / 80_000f, s.fractionAt(2_000L), 1e-6f)
    }

    // Arming the ending mid-cycle swaps the length but reports the same boundary: the display keeps running.
    @Test
    fun aLengthChangeMidCycleNeverStallsIt() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L)
            .next(at(4_000L), clockMs = 4_000L)
            .next(at(4_000L, durationMs = 100_000L), clockMs = 7_000L)
        assertEquals(100_000L, s.durationMs)
        listOf(7_000L, 7_250L, 7_500L, 7_750L, 8_000L).forEach { clock ->
            assertEquals(clock.toFloat(), s.positionAt(clock), "stalled at $clock")
        }
        assertEquals(7_500f / 100_000f, s.fractionAt(7_500L), 1e-6f)
    }

    // Updates stalled: it runs at most a cycle past the last one, so a late update never has to jump it back.
    @Test
    fun itRunsNoMoreThanACyclePastTheLastUpdate() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L).next(at(2_000L), clockMs = 2_000L)
        assertEquals(3_000f, s.positionAt(3_000L))
        assertEquals(4_000f, s.positionAt(4_000L))
        assertEquals(4_000f, s.positionAt(10_000L))
        val late = s.next(at(4_000L), clockMs = 10_000L)
        assertEquals(4_000f, late.positionAt(10_000L))
        assertEquals(4_500f, late.positionAt(10_500L))
    }

    // The tracker's cadence: one update a 2 s loop-cycle, each 0-200 ms late. The display runs on
    // between them, never backs up, and stays within a poll of the song.
    @Test
    fun aSongOfLateCycleUpdatesPlaysSmoothly() {
        val cycle = 2_000L
        val lateness = listOf(120L, 0L, 200L, 40L, 180L, 10L, 90L, 200L, 0L, 60L)
        var s = SmoothPosition.start(at(0L), clockMs = 0L)
        var arrivals = lateness.mapIndexed { i, late -> (i + 1) * cycle + late to (i + 1) * cycle }
        var last = 0f
        for (clock in 0L..lateness.size * cycle step 16) {
            while (arrivals.isNotEmpty() && arrivals.first().first <= clock) {
                s = s.next(at(arrivals.first().second), clock)
                arrivals = arrivals.drop(1)
            }
            val shown = s.positionAt(clock)
            assertTrue(shown >= last, "it went back at $clock: $last -> $shown")
            assertTrue(abs(shown - clock) <= 216f, "at $clock it showed $shown")
            last = shown
        }
    }
}
