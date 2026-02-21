package org.balch.orpheus.core.gestures

/** The five tracked fingers of a hand. */
enum class Finger {
    THUMB, INDEX, MIDDLE, RING, PINKY;

    /** The MediaPipe landmark index for this finger's tip. */
    val tipIndex: Int get() = when (this) {
        THUMB -> LandmarkIndex.THUMB_TIP
        INDEX -> LandmarkIndex.INDEX_TIP
        MIDDLE -> LandmarkIndex.MIDDLE_TIP
        RING -> LandmarkIndex.RING_TIP
        PINKY -> LandmarkIndex.PINKY_TIP
    }

    /** The MediaPipe landmark index for this finger's PIP joint (IP for thumb). */
    val pipIndex: Int get() = when (this) {
        THUMB -> LandmarkIndex.THUMB_IP
        INDEX -> LandmarkIndex.INDEX_PIP
        MIDDLE -> LandmarkIndex.MIDDLE_PIP
        RING -> LandmarkIndex.RING_PIP
        PINKY -> LandmarkIndex.PINKY_PIP
    }
}

/**
 * Tracked state of a single fingertip.
 * Coordinates are normalized [0,1] in camera-image space.
 */
data class FingerState(
    val finger: Finger,
    val tipX: Float,
    val tipY: Float,
    val tipZ: Float,
    val isPressed: Boolean,
    val isExtended: Boolean = false,
    val handedness: Handedness = Handedness.RIGHT,
)

/**
 * Enhanced pinch state with spatial midpoint for context-sensitive behavior.
 * The midpoint determines whether the pinch targets a pad (envelope speed)
 * or empty space (pitch bender).
 */
data class PinchState(
    val isPinching: Boolean,
    val midpointX: Float,
    val midpointY: Float,
    val strength: Float,
)
