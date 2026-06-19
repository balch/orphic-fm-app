package org.balch.orpheus.core.review

import org.balch.orpheus.core.engagement.EngagementAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewGateTest {

    private fun ReviewGate.counted(action: EngagementAction, lifetimeTotal: Int = 0, songsCompleted: Int = 0) =
        onEngagement(action, lifetimeTotal, songsCompleted) as ReviewGate.Outcome.Counted

    @Test fun pauseBeforeAnySongIsIgnored() {
        val gate = ReviewGate()
        // Even with counts that would otherwise be eligible, PAUSE pre-song does not qualify.
        assertEquals(
            ReviewGate.Outcome.Ignored,
            gate.onEngagement(EngagementAction.PAUSE, lifetimeTotal = 999, songsCompleted = 0),
        )
    }

    @Test fun pauseAfterASongCounts() {
        val gate = ReviewGate()
        // PAUSE session=1 (< 7) and total=1 (< 10) -> counted, but not yet a launch.
        assertEquals(
            ReviewGate.Outcome.Counted(newLifetimeTotal = 1, launchReview = false),
            gate.onEngagement(EngagementAction.PAUSE, lifetimeTotal = 0, songsCompleted = 1),
        )
    }

    @Test fun lifetimeTotalIsIncrementedFromTheSuppliedValue() {
        val gate = ReviewGate()
        assertEquals(5, gate.counted(EngagementAction.VIBE_SELECT, lifetimeTotal = 4).newLifetimeTotal)
    }

    @Test fun launchesWhenSessionCountReachesTheActionThreshold() {
        val gate = ReviewGate()
        val action = EngagementAction.TRANSITION_SELECT // session threshold 3
        // lifetimeTotal stays 0 so only the session path can trip.
        repeat(action.sessionEngagement - 1) {
            assertFalse(gate.counted(action).launchReview, "should not launch before threshold")
        }
        assertTrue(gate.counted(action).launchReview, "should launch at the session threshold")
    }

    @Test fun launchesViaLifetimePathEvenWhenSessionIsLow() {
        val gate = ReviewGate()
        val action = EngagementAction.TRANSITION_SELECT // total threshold 6
        // First event of the session, but lifetime is one below threshold -> the +1 crosses it.
        assertTrue(gate.counted(action, lifetimeTotal = action.totalEngagement - 1).launchReview)
    }

    @Test fun launchesUpToTheAttemptCapThenStops() {
        val gate = ReviewGate()
        val action = EngagementAction.TRANSITION_SELECT // session threshold 3
        // Re-attempting per session is intentional (Play returns no success signal); the
        // gate only bounds it at MAX_ATTEMPTS_PER_SESSION across the launch.
        val launches = (1..20).count { gate.counted(action).launchReview }
        assertEquals(ReviewPolicy.MAX_ATTEMPTS_PER_SESSION, launches)
    }

    @Test fun keepsCountingLifetimeTotalAfterTheAttemptCapIsReached() {
        val gate = ReviewGate()
        val action = EngagementAction.TRANSITION_SELECT
        // Exhaust the attempt cap, then assert further engagement still accrues the total.
        repeat(action.sessionEngagement + ReviewPolicy.MAX_ATTEMPTS_PER_SESSION) { gate.counted(action) }
        val after = gate.counted(action, lifetimeTotal = 41)
        assertFalse(after.launchReview, "must not launch once the attempt cap is reached")
        assertEquals(42, after.newLifetimeTotal, "lifetime total must keep accumulating")
    }

    @Test fun sessionCountsAreTrackedPerAction() {
        val gate = ReviewGate()
        val a = EngagementAction.GAIN_ADJUST      // session threshold 7
        val b = EngagementAction.INSTRUMENTS_OPEN // session threshold 10
        // Fire `a` up to one below its threshold — never launches.
        repeat(a.sessionEngagement - 1) { assertFalse(gate.counted(a).launchReview) }
        // A single `b` must NOT launch: if counts bled across actions, b would already be at 7.
        assertFalse(gate.counted(b).launchReview, "per-action counts must not bleed together")
        // One more `a` reaches a's threshold and launches.
        assertTrue(gate.counted(a).launchReview)
    }
}
