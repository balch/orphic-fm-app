package org.balch.orpheus.core.review

import org.balch.orpheus.core.engagement.EngagementAction

/**
 * Pure decision for whether to attempt the Google Play in-app review flow now.
 *
 * A user becomes *eligible* once they have engaged enough this session or over
 * their lifetime — the per-action thresholds live on [EngagementAction] as
 * [EngagementAction.sessionEngagement] / [EngagementAction.totalEngagement].
 * Eligibility is never reset — we re-ask on later sessions and let Play's own
 * quota gate the display.
 * We cap attempts at [MAX_ATTEMPTS_PER_SESSION] per app launch purely to bound
 * `requestReviewFlow()` overhead (the costly IPC); Play decides whether a card
 * actually shows and gives no success/decline signal back.
 *
 * Pure and deterministic — unit-tested on the JVM with no Android/Play deps.
 */
object ReviewPolicy {

    const val MAX_ATTEMPTS_PER_SESSION = 5

    /**
     * Return true when we should attempt the review flow right now.
     *
     * @param sessionEngagementCount qualifying engagement actions THIS app launch
     * @param totalEngagementCount   lifetime qualifying engagement actions (persisted, never reset)
     * @param attemptsThisSession    review attempts already made THIS app launch
     */
    fun decide(
        action: EngagementAction,
        sessionEngagementCount: Int,
        totalEngagementCount: Int,
        attemptsThisSession: Int,
    ): Boolean =
        attemptsThisSession < MAX_ATTEMPTS_PER_SESSION &&
            (sessionEngagementCount >= action.sessionEngagement ||
                totalEngagementCount >= action.totalEngagement)
}
