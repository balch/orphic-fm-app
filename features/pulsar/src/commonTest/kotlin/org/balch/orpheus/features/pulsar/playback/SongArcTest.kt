package org.balch.orpheus.features.pulsar.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongArcTest {
    private val estimate = 100_000L

    @Test
    fun theSlowdownIsLinearToTheKneeAndReadsAtEstimateAtTheEstimate() {
        assertEquals(SlowFrom / 2f, slowedFraction(SlowFrom / 2f))
        assertEquals(SlowFrom, slowedFraction(SlowFrom))
        // No kink: just past the knee it still moves at the song's pace.
        assertEquals(0.001f, slowedFraction(SlowFrom + 0.001f) - SlowFrom, 1e-5f)
        assertEquals(AtEstimate, slowedFraction(1f), 0.001f)
        val far = listOf(1.2f, 1.5f, 2f, 5f, 50f).map(::slowedFraction)
        far.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // Past the estimate it keeps creeping, but never fills the ring before the end locks in.
        far.forEach { assertTrue(it <= SlowCeiling, "it passed the ceiling: $it") }
    }

    // The ceiling is where the curve heads, not a clamp: it never stops dead partway, so there is no
    // kink, and a late lock-in bends from whatever pace it still has.
    @Test
    fun theArcNeverStopsDeadShortOfTheCeiling() {
        val late = listOf(1.5f, 1.6f, 1.7f, 1.8f, 2f).map(::slowedFraction)
        late.zipWithNext().forEach { (a, b) -> assertTrue(b > a, "it stopped: $a -> $b in $late") }
        assertTrue(SlowCeiling in 0.91f..0.92f, "the ceiling moved to $SlowCeiling")
    }

    // The soft knee: just past SlowFrom it still keeps most of the song's pace.
    @Test
    fun theSlowdownBrakesGentlyAtTheKnee() {
        val pace = (slowedFraction(0.761f) - slowedFraction(0.759f)) / 0.002f
        assertTrue(pace > 0.8f, "it braked to $pace of the song's pace by 0.76")
    }

    @Test
    fun untilTheEndIsKnownTheArcIsTheSlowdownOverTheEstimate() {
        // The length it is given is the tracker's forced or rolling end, which never moves the arc.
        assertEquals(slowedFraction(0.9f), songArc(90_000f, 240_000L, final = false, estimateMs = estimate, lockedAtMs = null))
        assertEquals(slowedFraction(1.3f), songArc(130_000f, 440_000L, final = false, estimateMs = estimate, lockedAtMs = null))
    }

    // A plain item, or a song whose end was known from the start: its own share.
    @Test
    fun aFinalLengthWithNoLockIsTheSongsOwnShare() {
        assertEquals(0.25f, songArc(30_000f, 120_000L, final = true, estimateMs = 120_000L, lockedAtMs = null))
    }

    @Test
    fun aLockedEndLeavesTheSlowdownWhereItStoodAndLandsOnTheEnd() {
        for ((lock, end) in listOf(76_000L to 90_000L, 80_000L to 84_000L, 100_000L to 150_000L, 100_000L to 400_000L)) {
            fun arc(ms: Float) = songArc(ms, end, final = true, estimateMs = estimate, lockedAtMs = lock)
            assertEquals(slowedFraction(lock.toFloat() / estimate), arc(lock.toFloat()), 1e-5f, "lock $lock end $end")
            assertEquals(1f, arc(end.toFloat()), 1e-5f, "lock $lock end $end")
            val frames = (lock..end step 250L).map { arc(it.toFloat()) }
            frames.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "it went back: $a -> $b (lock $lock end $end)") }
        }
    }

    // No kink: the pace just after the lock is the pace just before it, even while it catches up.
    @Test
    fun theLockKeepsTheSlowdownsPace() {
        fun arc(ms: Float) = songArc(ms, 90_000L, final = true, estimateMs = estimate, lockedAtMs = 76_000L)
        val before = slowedFraction(76_000f / estimate) - slowedFraction(75_980f / estimate)
        assertEquals(1f, (arc(76_020f) - arc(76_000f)) / before, 0.05f)
    }

    // An end sooner than the slowdown allowed for: the arc closes on the song within CatchUpMs, then is the song's share.
    @Test
    fun anEarlyEndCatchesUpToTheSongThenRidesIt() {
        fun arc(ms: Long) = songArc(ms.toFloat(), 90_000L, final = true, estimateMs = estimate, lockedAtMs = 76_000L)
        assertTrue(arc(76_000L) < 76_000f / 90_000f - 0.05f, "no gap to close at the lock")
        for (ms in (76_000L + CatchUpMs.toLong())..90_000L step 250L) assertEquals(ms / 90_000f, arc(ms), 1e-6f, "at $ms")
    }

    // A short last section: it has joined the song by halfway through, never in a rush at the end.
    @Test
    fun aShortLastSectionRidesTheSongForItsSecondHalf() {
        fun arc(ms: Long) = songArc(ms.toFloat(), 84_000L, final = true, estimateMs = estimate, lockedAtMs = 80_000L)
        for (ms in 82_000L..84_000L step 100L) assertEquals(ms / 84_000f, arc(ms), 1e-6f, "at $ms")
    }

    // An end later than the slowdown allowed for: the slowdown runs on until the song's share catches it, then that share.
    @Test
    fun aLateEndKeepsTheSlowdownUntilTheSongCatchesUp() {
        fun arc(ms: Long) = songArc(ms.toFloat(), 150_000L, final = true, estimateMs = estimate, lockedAtMs = 100_000L)
        assertEquals(slowedFraction(1.2f), arc(120_000L))
        assertEquals(140_000f / 150_000f, arc(140_000L), 1e-6f)
    }

    // A display a little behind the tracker's lock still draws the slowdown there, never the bend run backwards.
    @Test
    fun beforeTheLockItIsStillTheSlowdown() {
        assertEquals(slowedFraction(0.95f), songArc(95_000f, 150_000L, final = true, estimateMs = estimate, lockedAtMs = 100_000L))
    }
}
