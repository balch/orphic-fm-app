package org.balch.orpheus.core.mediapipe

import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.Handedness

/** MediaPipe's hand model is 21 points; see [HandLandmark]'s kdoc for the layout. */
const val LANDMARKS_PER_HAND = 21

/** One handedness marker plus 21 xyz triples. */
const val FLOATS_PER_HAND = 1 + LANDMARKS_PER_HAND * 3

/**
 * Parses the flat landmark array both native producers hand across: the desktop JNI bridge and
 * the iOS Swift bridge.
 *
 * Layout: `[numHands, hand0_handedness, hand0_x0,y0,z0, ... x20,y20,z20, hand1_...]`, so
 * [FLOATS_PER_HAND] floats per hand after the leading count.
 *
 * Shared rather than reimplemented per platform: this is index arithmetic against a
 * hand-assembled buffer, it fails silently rather than loudly when it is wrong, and two copies
 * of it would drift. Returns `null` for "no hands this frame", which is what both trackers
 * publish downstream.
 *
 * Every length is checked against what is actually present. The producers are Swift and C, both
 * assembling this by hand, and a short buffer must drop hands rather than take the process down
 * in the middle of a performance.
 */
fun parseHandLandmarkWire(data: FloatArray, timestampMs: Long): HandTrackingResult? {
    if (data.isEmpty()) return null
    val claimed = data[0].toInt()
    if (claimed <= 0) return null
    // Trust the buffer over the count: the count is just the first float in the same array.
    val available = (data.size - 1) / FLOATS_PER_HAND
    val numHands = minOf(claimed, available)
    if (numHands <= 0) return null

    val hands = (0 until numHands).map { h ->
        val base = 1 + h * FLOATS_PER_HAND
        // Inverted: the camera image is mirrored, so MediaPipe's "Right" is the user's LEFT
        // hand. Consumers assign different roles to each hand, so getting this backwards
        // silently swaps which hand does what rather than failing.
        // Matches AndroidHandTracker.resolveHandedness.
        val handedness = if (data[base] >= 0.5f) Handedness.LEFT else Handedness.RIGHT
        val landmarks = (0 until LANDMARKS_PER_HAND).map { i ->
            val off = base + 1 + i * 3
            HandLandmark(x = data[off], y = data[off + 1], z = data[off + 2])
        }
        TrackedHand(landmarks, handedness)
    }
    return HandTrackingResult(hands = hands, frameSequence = timestampMs)
}
