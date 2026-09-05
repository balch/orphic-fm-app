package org.balch.orpheus.features.mediapipe

import kotlin.math.sqrt
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.LandmarkIndex

/** A point in the same normalized 0..1 space [HandLandmark] uses. */
internal data class LensPoint(val x: Float, val y: Float)

/** One rounded lens segment: the capsule (stadium) from [start] to [end] with [radius]. */
internal data class LensCapsule(val start: LensPoint, val end: LensPoint, val radius: Float)

/**
 * The articulated hand lens in normalized coordinates: four finger capsules, one thumb
 * capsule, and the palm polygon. Pure geometry so it tests without rendering; the mapping to
 * a Compose Path lives in HandGlassOverlay.kt.
 *
 * Invariants the builder guarantees and the tests assert:
 * - every capsule extent (endpoint inflated by radius) stays inside 0..1
 * - the palm polygon winds clockwise in screen coordinates for either handedness, matching
 *   the clockwise capsule outlines so NonZero fill cannot carve holes where they overlap
 */
internal data class HandLensGeometry(
    val fingerCapsules: List<LensCapsule>,
    val thumbCapsule: LensCapsule,
    val palmPolygon: List<LensPoint>,
) {
    val capsules: List<LensCapsule> get() = fingerCapsules + thumbCapsule
}

/** Finger capsule radius as a fraction of the wrist-to-middle-knuckle span. */
private const val FINGER_RADIUS_FRACTION = 0.13f

/** The thumb reads wider than the fingers. */
private const val THUMB_RADIUS_FRACTION = 0.17f

/** MCP-to-TIP index pairs for the four fingers, in skeleton order. */
private val FINGERS = listOf(
    LandmarkIndex.INDEX_MCP to LandmarkIndex.INDEX_TIP,
    LandmarkIndex.MIDDLE_MCP to LandmarkIndex.MIDDLE_TIP,
    LandmarkIndex.RING_MCP to LandmarkIndex.RING_TIP,
    LandmarkIndex.PINKY_MCP to LandmarkIndex.PINKY_TIP,
)

/** Palm perimeter: wrist first, then around the knuckle line. */
private val PALM = intArrayOf(
    LandmarkIndex.WRIST,
    LandmarkIndex.THUMB_CMC,
    LandmarkIndex.INDEX_MCP,
    LandmarkIndex.MIDDLE_MCP,
    LandmarkIndex.RING_MCP,
    LandmarkIndex.PINKY_MCP,
)

/** Builds the lens for one hand, or null when there are not 21 landmarks to build from. */
internal fun handLensGeometry(landmarks: List<HandLandmark>): HandLensGeometry? {
    if (landmarks.size < 21) return null
    val span = distance(landmarks[LandmarkIndex.WRIST], landmarks[LandmarkIndex.MIDDLE_MCP])
    if (span <= 0f) return null

    val fingerRadius = span * FINGER_RADIUS_FRACTION
    val fingers = FINGERS.map { (mcp, tip) ->
        capsule(landmarks[mcp], landmarks[tip], fingerRadius)
    }
    // CMC rather than MCP as the thumb root: the palm polygon has a vertex at THUMB_CMC, so
    // starting the capsule there bridges thumb and palm without a visible gap.
    val thumb = capsule(
        landmarks[LandmarkIndex.THUMB_CMC],
        landmarks[LandmarkIndex.THUMB_TIP],
        span * THUMB_RADIUS_FRACTION,
    )
    val palm = PALM.map { landmarks[it].toLensPoint(inset = 0f) }
    return HandLensGeometry(fingers, thumb, clockwise(palm))
}

private fun capsule(a: HandLandmark, b: HandLandmark, radius: Float): LensCapsule {
    val r = radius.coerceAtMost(0.5f)
    return LensCapsule(a.toLensPoint(inset = r), b.toLensPoint(inset = r), r)
}

/**
 * Clamp into the unit square, pulled in by [inset] so an endpoint inflated by its radius
 * still fits. A fingertip at the frame edge shifts inward by its own radius — about 2% of
 * the frame — which is invisible next to the lens staying inside its box.
 */
private fun HandLandmark.toLensPoint(inset: Float): LensPoint =
    LensPoint(x.coerceIn(inset, 1f - inset), y.coerceIn(inset, 1f - inset))

private fun distance(a: HandLandmark, b: HandLandmark): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Normalize winding. Landmarks are y-down screen coordinates, so a positive shoelace sum
 * renders clockwise — the same direction as the capsule outlines' positive arc sweeps. A
 * mirrored (right) hand yields the opposite sign and is reversed here; without this, NonZero
 * fill would carve holes wherever the palm overlaps a capsule base on one handedness.
 */
private fun clockwise(points: List<LensPoint>): List<LensPoint> {
    var sum = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return if (sum >= 0f) points else points.reversed()
}
