package org.balch.orpheus.core.mediapipe

import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness

/**
 * A single tracked hand with its landmarks and handedness.
 */
data class TrackedHand(
    val landmarks: List<HandLandmark>,
    val handedness: Handedness,
    val gestureName: String? = null,
    val gestureConfidence: Float = 0f,
)

/**
 * Result from the platform-specific hand tracker.
 * Contains zero, one, or two tracked hands per frame.
 */
data class HandTrackingResult(
    val hands: List<TrackedHand>,
    /** Monotonically increasing frame identifier. On Android this is the real timestamp in ms;
     *  on desktop it is a frame sequence counter. Do not use for wall-clock timing. */
    val frameSequence: Long,
)
