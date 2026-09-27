package org.balch.orpheus.djapp

import org.balch.orpheus.features.pulsar.playback.AtEstimate
import org.balch.orpheus.features.pulsar.playback.SlowCeiling
import org.balch.orpheus.features.pulsar.playback.slowedFraction
import org.balch.orpheus.features.pulsar.playback.songArc
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The clock here is the play clock: it runs only while playing (see ProgressWave.playMs). */
class SmoothProgressTest {
    private fun at(
        positionMs: Long,
        durationMs: Long = 120_000L,
        song: String = "Rust Belt",
        final: Boolean = true,
        estimateMs: Long = durationMs,
        lockedAtMs: Long? = null,
    ) = SongPosition(song, positionMs, durationMs, final, estimateMs, lockedAtMs = lockedAtMs)

    /** Plays [updates] (each at the play-clock time it lands) and samples the fraction every frame up to [untilMs]. */
    private fun play(updates: List<Pair<Long, SongPosition>>, untilMs: Long): List<Pair<Long, Float>> {
        var s = SmoothPosition.start(updates.first().second, updates.first().first)
        var pending = updates.drop(1)
        return (updates.first().first..untilMs step 16).map { clock ->
            while (pending.isNotEmpty() && pending.first().first <= clock) {
                s = s.next(pending.first().second, clock)
                pending = pending.drop(1)
            }
            clock to s.fractionAt(clock)
        }
    }

    private fun List<Pair<Long, Float>>.at(clock: Long): Float = last { it.first <= clock }.second

    /** How far the arc moved from [fromMs] to [toMs]. */
    private fun List<Pair<Long, Float>>.moved(fromMs: Long, toMs: Long): Float = at(toMs) - at(fromMs)

    private fun List<Pair<Long, Float>>.assertSmooth() = zipWithNext().forEach { (a, b) ->
        assertTrue(b.second >= a.second, "it went back at ${b.first}: ${a.second} -> ${b.second}")
        assertTrue(b.second - a.second < 0.01f, "it jumped at ${b.first}: ${a.second} -> ${b.second}")
    }

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

    // A 2 s loop-cycle against a 100 s estimate: it keeps pace to the knee, then slows, and the estimate
    // running out (the tracker swaps in the longest length) never pulls it back.
    @Test
    fun anEstimatedEndSlowsTheArcAndNeverBacksItUp() {
        val updates = (0L..130_000L step 2_000L).map { ms ->
            ms to at(ms, durationMs = if (ms < 100_000L) 100_000L else 140_000L, final = false, estimateMs = 100_000L)
        }
        val frames = play(updates, untilMs = 130_000L)
        frames.assertSmooth()
        assertEquals(0.5f, frames.at(50_000L), 0.002f)
        assertEquals(AtEstimate, frames.at(100_000L), 0.005f)
        assertTrue(frames.last().second <= SlowCeiling, "it ran on to ${frames.last().second}")
    }

    // Past the estimate the tracker reports the forced end, but a seek or a resume after a gap still
    // scales the slowdown by the song's estimate, so the arc never drops back.
    @Test
    fun aJumpPastTheEstimateKeepsTheArcsScale() {
        val s = SmoothPosition.start(at(220_000L, 240_000L, final = false, estimateMs = 217_000L), clockMs = 0L)
        val before = s.fractionAt(1_000L)
        val resumed = s.next(at(230_000L, 240_000L, final = false, estimateMs = 217_000L), clockMs = 1_000L)
        assertEquals(230_000f, resumed.positionAt(1_000L))
        assertEquals(slowedFraction(230_000f / 217_000f), resumed.fractionAt(1_000L), 1e-6f)
        assertTrue(resumed.fractionAt(1_000L) >= before, "it dropped from $before to ${resumed.fractionAt(1_000L)}")
    }

    // A ring mounted late (a layout change, another surface) draws the arc one that saw the whole song draws.
    @Test
    fun aRemountPastTheEstimateDrawsTheSameArc() {
        val updates = (0L..226_000L step 2_000L).map { ms ->
            ms to at(ms, durationMs = if (ms < 217_000L) 217_000L else 240_000L, final = false, estimateMs = 217_000L)
        }
        val seen = play(updates, untilMs = 226_000L).at(226_000L)
        val mounted = SmoothPosition.start(updates.last().second, clockMs = 226_000L).fractionAt(226_000L)
        assertEquals(seen, mounted, 1e-4f)
    }

    // The real end is later than the estimate: the slowdown runs on from AtEstimate until the song's
    // share catches it, then rides that share to the end.
    @Test
    fun aLateEndLocksInWithoutAJumpAndLandsOnTheEnd() {
        val updates = (0L..150_000L step 2_000L).map { ms ->
            ms to if (ms < 100_000L) at(ms, durationMs = 100_000L, final = false) else at(ms, 150_000L, estimateMs = 100_000L, lockedAtMs = 100_000L)
        }
        val frames = play(updates, untilMs = 150_000L)
        frames.assertSmooth()
        assertEquals(AtEstimate, frames.at(100_000L), 0.005f)
        assertEquals(1f, frames.last().second, 0.001f)
        // No kink: the pace just after the lock is the pace just before it.
        assertEquals(1f, frames.moved(100_000L, 100_096L) / frames.moved(99_904L, 100_000L), 0.05f)
    }

    // An end far past the estimate: the arc holds to the slowdown until the song passes it.
    @Test
    fun aVeryLateEndEasesOffWithoutBackingUp() {
        val updates = (0L..400_000L step 2_000L).map { ms ->
            ms to if (ms < 100_000L) at(ms, durationMs = 100_000L, final = false) else at(ms, 400_000L, estimateMs = 100_000L, lockedAtMs = 100_000L)
        }
        val frames = play(updates, untilMs = 400_000L)
        frames.assertSmooth()
        assertEquals(1f, frames.last().second, 0.001f)
    }

    // The real end comes sooner than the arc shows: it catches up to the song, then keeps the song's pace to the end.
    @Test
    fun anEarlyEndCatchesUpAndRidesTheSongToTheEnd() {
        val updates = (0L..90_000L step 2_000L).map { ms ->
            ms to if (ms < 80_000L) at(ms, durationMs = 100_000L, final = false) else at(ms, 90_000L, estimateMs = 100_000L, lockedAtMs = 80_000L)
        }
        val frames = play(updates, untilMs = 90_000L)
        frames.assertSmooth()
        assertTrue(frames.at(80_000L) < 0.8f)
        assertEquals(1f, frames.last().second, 0.001f)
        // No kink: the pace just after the lock is the pace just before it.
        assertEquals(1f, frames.moved(80_000L, 80_016L) / frames.moved(79_984L, 80_000L), 0.05f)
        // Caught up by halfway through the section, then no rush at the end: each second moves as the song does.
        for (ms in 85_000L until 90_000L step 1_000L) assertEquals(1f / 90f, frames.moved(ms, ms + 1_000L), 2e-4f, "at $ms")
    }

    // The ending armed in the last section already playing: the length changes with no new boundary.
    // The arc bends from the tracker's boundary, a second behind the display, which it matches to
    // the first order: the step is far under a pixel.
    @Test
    fun aLockInMidCycleNeverJumpsTheArc() {
        val s = SmoothPosition.start(at(0L, 100_000L, final = false), clockMs = 0L)
            .next(at(80_000L, 100_000L, final = false), clockMs = 80_000L)
        val before = s.fractionAt(81_000L)
        val locked = s.next(at(80_000L, 120_000L, estimateMs = 100_000L, lockedAtMs = 80_000L), clockMs = 81_000L)
        assertEquals(81_000f, locked.positionAt(81_000L))
        assertEquals(before, locked.fractionAt(81_000L), 5e-4f)
        assertTrue(locked.fractionAt(82_000L) > before)
    }

    // A ring mounted in the last section (a layout change, another surface) draws the arc one that
    // saw the lock-in draws, not a straight line from where it opened.
    @Test
    fun aRemountAfterLockInDrawsTheSameArc() {
        val updates = (0L..130_000L step 2_000L).map { ms ->
            ms to if (ms < 100_000L) at(ms, 100_000L, final = false) else at(ms, 150_000L, estimateMs = 100_000L, lockedAtMs = 100_000L)
        }
        val seen = play(updates, untilMs = 130_000L).at(130_000L)
        val mounted = SmoothPosition.start(updates.last().second, clockMs = 130_000L).fractionAt(130_000L)
        assertEquals(seen, mounted, 1e-4f)
        assertEquals(songArc(130_000f, 150_000L, true, 100_000L, 100_000L), mounted, 1e-6f)
    }

    // Arming the ending mid-cycle swaps the length but reports the same boundary: the display keeps
    // running, and draws the arc for the new length.
    @Test
    fun aLengthChangeMidCycleNeverStallsIt() {
        val s = SmoothPosition.start(at(0L), clockMs = 0L)
            .next(at(4_000L), clockMs = 4_000L)
            .next(at(4_000L, durationMs = 100_000L), clockMs = 7_000L)
        assertEquals(100_000L, s.durationMs)
        listOf(7_000L, 7_250L, 7_500L, 7_750L, 8_000L).forEach { clock ->
            assertEquals(clock.toFloat(), s.positionAt(clock), "stalled at $clock")
        }
        assertEquals(7_000f / 100_000f, s.fractionAt(7_000L), 1e-6f)
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
