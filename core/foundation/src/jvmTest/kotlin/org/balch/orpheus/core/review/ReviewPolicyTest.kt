package org.balch.orpheus.core.review

import org.balch.orpheus.core.engagement.EngagementAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPolicyTest {

    // Reference the action's OWN thresholds rather than literals so these cases
    // stay correct as the per-action values on EngagementAction are tuned.
    private val action = EngagementAction.PAUSE

    @Test fun notEligibleBelowBothThresholds() =
        assertFalse(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = action.sessionEngagement - 1,
                totalEngagementCount = action.totalEngagement - 1,
                attemptsThisSession = 0,
            )
        )

    @Test fun eligibleAtSessionThreshold() =
        assertTrue(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = action.sessionEngagement,
                totalEngagementCount = 0,
                attemptsThisSession = 0,
            )
        )

    @Test fun eligibleAtTotalThreshold() =
        assertTrue(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = 0,
                totalEngagementCount = action.totalEngagement,
                attemptsThisSession = 0,
            )
        )

    @Test fun eligibleWhenEitherThresholdExceeded() =
        assertTrue(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = action.sessionEngagement + 10,
                totalEngagementCount = action.totalEngagement + 100,
                attemptsThisSession = ReviewPolicy.MAX_ATTEMPTS_PER_SESSION - 1,
            )
        )

    @Test fun notEligibleOnceAttemptCapReached() =
        assertFalse(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = 99,
                totalEngagementCount = 99,
                attemptsThisSession = ReviewPolicy.MAX_ATTEMPTS_PER_SESSION,
            )
        )

    @Test fun lastAttemptBelowCapIsAllowed() =
        assertTrue(
            ReviewPolicy.decide(
                action,
                sessionEngagementCount = action.sessionEngagement,
                totalEngagementCount = 0,
                attemptsThisSession = ReviewPolicy.MAX_ATTEMPTS_PER_SESSION - 1,
            )
        )

    @Test fun thresholdsAreEvaluatedPerAction() {
        // Tuning guard: at a session count that clears a low-threshold action but
        // not a high-threshold one, only the low action is eligible. total = 0 so
        // the lifetime path can't mask the per-action difference. Skips silently
        // if every action happens to share one threshold (nothing to differentiate).
        val low = EngagementAction.entries.minBy { it.sessionEngagement }
        val high = EngagementAction.entries.maxBy { it.sessionEngagement }
        if (low.sessionEngagement == high.sessionEngagement) return

        val count = high.sessionEngagement - 1
        assertTrue(
            ReviewPolicy.decide(low, sessionEngagementCount = count, totalEngagementCount = 0, attemptsThisSession = 0),
            "low-threshold action ($low) should be eligible at session=$count",
        )
        assertFalse(
            ReviewPolicy.decide(high, sessionEngagementCount = count, totalEngagementCount = 0, attemptsThisSession = 0),
            "high-threshold action ($high) should NOT be eligible at session=$count",
        )
    }
}
