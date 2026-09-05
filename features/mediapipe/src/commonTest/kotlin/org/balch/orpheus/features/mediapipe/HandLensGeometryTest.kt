package org.balch.orpheus.features.mediapipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.balch.orpheus.core.gestures.HandLandmark
import org.balch.orpheus.core.gestures.LandmarkIndex

class HandLensGeometryTest {

    /**
     * Open hand facing the camera, fingers up, comfortably inside the frame. Same
     * fixture-building idiom as GestureLoggingTest.thumbsUp().
     */
    private fun openHand(): List<HandLandmark> {
        val base = MutableList(21) { HandLandmark(0.5f, 0.5f, 0f) }
        fun set(index: Int, x: Float, y: Float) { base[index] = HandLandmark(x, y, 0f) }
        set(LandmarkIndex.WRIST, 0.50f, 0.85f)
        set(LandmarkIndex.THUMB_CMC, 0.38f, 0.75f)
        set(LandmarkIndex.THUMB_MCP, 0.32f, 0.68f)
        set(LandmarkIndex.THUMB_IP, 0.28f, 0.62f)
        set(LandmarkIndex.THUMB_TIP, 0.25f, 0.57f)
        set(LandmarkIndex.INDEX_MCP, 0.40f, 0.55f)
        set(LandmarkIndex.INDEX_PIP, 0.39f, 0.45f)
        set(LandmarkIndex.INDEX_DIP, 0.38f, 0.38f)
        set(LandmarkIndex.INDEX_TIP, 0.37f, 0.30f)
        set(LandmarkIndex.MIDDLE_MCP, 0.48f, 0.53f)
        set(LandmarkIndex.MIDDLE_PIP, 0.48f, 0.42f)
        set(LandmarkIndex.MIDDLE_DIP, 0.48f, 0.34f)
        set(LandmarkIndex.MIDDLE_TIP, 0.48f, 0.25f)
        set(LandmarkIndex.RING_MCP, 0.56f, 0.55f)
        set(LandmarkIndex.RING_PIP, 0.57f, 0.44f)
        set(LandmarkIndex.RING_DIP, 0.57f, 0.37f)
        set(LandmarkIndex.RING_TIP, 0.58f, 0.29f)
        set(LandmarkIndex.PINKY_MCP, 0.63f, 0.58f)
        set(LandmarkIndex.PINKY_PIP, 0.65f, 0.50f)
        set(LandmarkIndex.PINKY_DIP, 0.66f, 0.44f)
        set(LandmarkIndex.PINKY_TIP, 0.67f, 0.38f)
        return base
    }

    /** The same hand reported the other way round, as a mirror image. */
    private fun mirrored(landmarks: List<HandLandmark>): List<HandLandmark> =
        landmarks.map { it.copy(x = 1f - it.x) }

    /** Ray casting. Robust enough for the convex test polygon. */
    private fun contains(polygon: List<LensPoint>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) &&
                x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Positive in y-down screen coordinates means the sequence renders clockwise. */
    private fun shoelace(polygon: List<LensPoint>): Float {
        var sum = 0f
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum
    }

    @Test
    fun `builds five capsules with the thumb fattest`() {
        val geometry = assertNotNull(handLensGeometry(openHand()))
        assertEquals(4, geometry.fingerCapsules.size)
        assertEquals(5, geometry.capsules.size)
        assertTrue(geometry.capsules.all { it.radius > 0f })
        assertTrue(geometry.thumbCapsule.radius > geometry.fingerCapsules[0].radius)
    }

    @Test
    fun `finger capsules run knuckle to tip`() {
        val hand = openHand()
        val geometry = assertNotNull(handLensGeometry(hand))
        val index = geometry.fingerCapsules[0]
        assertEquals(hand[LandmarkIndex.INDEX_MCP].x, index.start.x, 1e-6f)
        assertEquals(hand[LandmarkIndex.INDEX_MCP].y, index.start.y, 1e-6f)
        assertEquals(hand[LandmarkIndex.INDEX_TIP].x, index.end.x, 1e-6f)
        assertEquals(hand[LandmarkIndex.INDEX_TIP].y, index.end.y, 1e-6f)
    }

    @Test
    fun `palm polygon encloses the palm centre`() {
        val hand = openHand()
        val geometry = assertNotNull(handLensGeometry(hand))
        val wrist = hand[LandmarkIndex.WRIST]
        val middleMcp = hand[LandmarkIndex.MIDDLE_MCP]
        assertTrue(
            contains(
                geometry.palmPolygon,
                x = (wrist.x + middleMcp.x) / 2f,
                y = (wrist.y + middleMcp.y) / 2f,
            ),
            "the palm centre must sit inside the palm polygon",
        )
    }

    @Test
    fun `palm winds clockwise for either handedness`() {
        val left = assertNotNull(handLensGeometry(openHand()))
        val right = assertNotNull(handLensGeometry(mirrored(openHand())))
        assertTrue(shoelace(left.palmPolygon) >= 0f)
        assertTrue(
            shoelace(right.palmPolygon) >= 0f,
            "a mirrored hand reverses the raw vertex order; the builder must normalize it",
        )
    }

    @Test
    fun `bounds stay inside the unit square even at the frame edge`() {
        val edgy = openHand().map { HandLandmark(it.x, (it.y - 0.28f).coerceAtLeast(0f), it.z) }
        for (hand in listOf(openHand(), edgy)) {
            val geometry = assertNotNull(handLensGeometry(hand))
            for (capsule in geometry.capsules) {
                for (point in listOf(capsule.start, capsule.end)) {
                    assertTrue(point.x - capsule.radius >= 0f)
                    assertTrue(point.x + capsule.radius <= 1f)
                    assertTrue(point.y - capsule.radius >= 0f)
                    assertTrue(point.y + capsule.radius <= 1f)
                }
            }
            assertTrue(geometry.palmPolygon.all { it.x in 0f..1f && it.y in 0f..1f })
        }
    }

    @Test
    fun `fewer than 21 landmarks builds nothing`() {
        assertNull(handLensGeometry(emptyList()))
        assertNull(handLensGeometry(openHand().dropLast(1)))
    }
}
