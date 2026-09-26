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
        assertTrue(SlowCeiling in 0.9f..0.91f, "the ceiling moved to $SlowCeiling")
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
    fun aLockedEndBendsFromWhereTheSlowdownStoodToLandOnTheEnd() {
        for ((lock, end) in listOf(80_000L to 90_000L, 100_000L to 150_000L, 100_000L to 400_000L)) {
            fun arc(ms: Float) = songArc(ms, end, final = true, estimateMs = estimate, lockedAtMs = lock)
            assertEquals(slowedFraction(lock.toFloat() / estimate), arc(lock.toFloat()), 1e-5f, "lock $lock end $end")
            assertEquals(1f, arc(end.toFloat()), 1e-5f, "lock $lock end $end")
            val frames = (lock..end step 250L).map { arc(it.toFloat()) }
            frames.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "it went back: $a -> $b (lock $lock end $end)") }
        }
    }

    // No kink: the pace just after the lock is the pace just before it, where it can keep up.
    @Test
    fun theLockKeepsTheSlowdownsPace() {
        val before = slowedFraction(100_000f / estimate) - slowedFraction(99_000f / estimate)
        val after = songArc(101_000f, 150_000L, final = true, estimateMs = estimate, lockedAtMs = 100_000L) -
            songArc(100_000f, 150_000L, final = true, estimateMs = estimate, lockedAtMs = 100_000L)
        assertEquals(1f, after / before, 0.05f)
    }

    // A display a little behind the tracker's lock still draws the slowdown there, never the bend run backwards.
    @Test
    fun beforeTheLockItIsStillTheSlowdown() {
        assertEquals(slowedFraction(0.95f), songArc(95_000f, 150_000L, final = true, estimateMs = estimate, lockedAtMs = 100_000L))
    }
}
