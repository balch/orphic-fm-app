package org.balch.orpheus.core.gestures

/**
 * A single hand landmark point with normalized coordinates.
 * Coordinates are in [0.0, 1.0] range, normalized by image dimensions.
 * z represents depth (smaller = closer to camera).
 *
 * Standard 21-point hand model:
 *  0: WRIST
 *  1-4: THUMB (CMC, MCP, IP, TIP)
 *  5-8: INDEX (MCP, PIP, DIP, TIP)
 *  9-12: MIDDLE (MCP, PIP, DIP, TIP)
 *  13-16: RING (MCP, PIP, DIP, TIP)
 *  17-20: PINKY (MCP, PIP, DIP, TIP)
 */
data class HandLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
)

/** Well-known landmark indices. */
object LandmarkIndex {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    /** All 5 fingertip indices, ordered thumb→pinky. */
    val FINGERTIPS = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
}

enum class Handedness { LEFT, RIGHT }

/**
 * The expected connections between landmarks for drawing the hand skeleton.
 * Each pair is (from, to) landmark indices.
 */
val HAND_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    // Thumb
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    // Index
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    // Middle
    0 to 9, 9 to 10, 10 to 11, 11 to 12,
    // Ring
    0 to 13, 13 to 14, 14 to 15, 15 to 16,
    // Pinky
    0 to 17, 17 to 18, 18 to 19, 19 to 20,
    // Palm
    5 to 9, 9 to 13, 13 to 17,
)
