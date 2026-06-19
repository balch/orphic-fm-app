package org.balch.orpheus.core.review

import org.balch.orpheus.core.engagement.EngagementAction

/**
 * Pure, platform-free engagement -> review-eligibility state machine, extracted from
 * the Play-backed manager so the decision logic is unit-testable without the Play SDK.
 *
 * Tracks per-action session counts for this app launch plus the count of review
 * attempts already made. Because the Play API gives no success/decline signal back,
 * it is fine — and in line with Play's guidance — to attempt the flow more than once
 * per session; we cap at [ReviewPolicy.MAX_ATTEMPTS_PER_SESSION] purely to bound the
 * `requestReviewFlow()` IPC overhead. Counting continues after the cap is reached so
 * persisted lifetime totals stay accurate for future sessions.
 *
 * Lifetime totals and the "songs completed this session" precondition are owned by the
 * caller and passed into [onEngagement]; eligibility itself is delegated to [ReviewPolicy].
 *
 * Not thread-safe: callers must invoke [onEngagement] from a single coroutine (the
 * engagement-event collector).
 */
class ReviewGate {

    private var attemptsThisSession = 0
    private val sessionCounts = mutableMapOf<EngagementAction, Int>()

    /**
     * Process one engagement [action].
     *
     * @param lifetimeTotal  persisted lifetime count for [action] BEFORE this event
     * @param songsCompleted full songs heard this session (PAUSE needs >= 1 to count)
     * @return [Outcome.Ignored] if the action does not qualify (so the caller persists
     *   nothing); otherwise [Outcome.Counted] with the new lifetime total to persist and
     *   whether the review flow should be launched now.
     */
    fun onEngagement(action: EngagementAction, lifetimeTotal: Int, songsCompleted: Int): Outcome {
        // PAUSE is only meaningful engagement once the user has actually heard a full song.
        if (action == EngagementAction.PAUSE && songsCompleted == 0) return Outcome.Ignored

        val session = (sessionCounts[action] ?: 0) + 1
        sessionCounts[action] = session
        val newTotal = lifetimeTotal + 1

        // decide() applies the per-session attempt cap; bump the counter on each launch so
        // the cap holds across the (up to MAX_ATTEMPTS_PER_SESSION) attempts per launch.
        val launch = ReviewPolicy.decide(action, session, newTotal, attemptsThisSession)
        if (launch) attemptsThisSession++

        return Outcome.Counted(newLifetimeTotal = newTotal, launchReview = launch)
    }

    sealed interface Outcome {
        /** Action did not qualify (e.g. PAUSE before any song completed); persist nothing. */
        data object Ignored : Outcome

        /** Action counted; persist [newLifetimeTotal]. Launch the review flow iff [launchReview]. */
        data class Counted(val newLifetimeTotal: Int, val launchReview: Boolean) : Outcome
    }
}
