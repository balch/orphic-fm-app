package org.balch.orpheus.core.mediapipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.balch.orpheus.core.gestures.Handedness

/** Builds one hand's 64 floats: handedness marker then 21 xyz triples. */
private fun handFloats(handednessMarker: Float, seed: Float): FloatArray =
    floatArrayOf(handednessMarker) + FloatArray(LANDMARKS_PER_HAND * 3) { i -> seed + i * 0.001f }

private fun wire(vararg hands: FloatArray): FloatArray =
    floatArrayOf(hands.size.toFloat()) + (hands.reduceOrNull { a, b -> a + b } ?: FloatArray(0))

class HandLandmarkWireTest {

    @Test
    fun `a hand's twenty-one landmarks come back in order`() {
        val result = assertNotNull(parseHandLandmarkWire(wire(handFloats(0f, 0.5f)), timestampMs = 7L))
        val hand = result.hands.single()
        assertEquals(LANDMARKS_PER_HAND, hand.landmarks.size)
        assertEquals(0.5f, hand.landmarks[0].x)
        assertEquals(0.501f, hand.landmarks[0].y)
        assertEquals(0.502f, hand.landmarks[0].z)
        // Last landmark starts at offset 20*3 = 60 into the triples.
        assertEquals(0.5f + 60 * 0.001f, hand.landmarks[20].x)
    }

    /**
     * The camera image is mirrored, so MediaPipe's "Right" is the user's LEFT hand. Consumers
     * assign different roles to each hand, so getting this backwards silently swaps which hand
     * does what. Matches AndroidHandTracker.resolveHandedness.
     */
    @Test
    fun `handedness is inverted because the camera is mirrored`() {
        val left = assertNotNull(parseHandLandmarkWire(wire(handFloats(1f, 0f)), 0L))
        assertEquals(Handedness.LEFT, left.hands.single().handedness)

        val right = assertNotNull(parseHandLandmarkWire(wire(handFloats(0f, 0f)), 0L))
        assertEquals(Handedness.RIGHT, right.hands.single().handedness)
    }

    @Test
    fun `both hands survive in order with their own handedness`() {
        val data = wire(handFloats(1f, 0.1f), handFloats(0f, 0.7f))
        val result = assertNotNull(parseHandLandmarkWire(data, 0L))
        assertEquals(2, result.hands.size)
        assertEquals(Handedness.LEFT, result.hands[0].handedness)
        assertEquals(Handedness.RIGHT, result.hands[1].handedness)
        assertEquals(0.1f, result.hands[0].landmarks[0].x)
        assertEquals(0.7f, result.hands[1].landmarks[0].x)
    }

    @Test
    fun `the timestamp becomes the frame sequence`() {
        val result = assertNotNull(parseHandLandmarkWire(wire(handFloats(0f, 0f)), timestampMs = 1234L))
        assertEquals(1234L, result.frameSequence)
    }

    @Test
    fun `no hands reads as null rather than an empty result`() {
        assertNull(parseHandLandmarkWire(floatArrayOf(0f), 0L))
    }

    @Test
    fun `an empty array is null rather than a crash`() {
        assertNull(parseHandLandmarkWire(FloatArray(0), 0L))
    }

    /**
     * The producer is Swift on iOS and JNI on desktop -- both hand-assemble this array, and a
     * truncated one must not take the process down mid-performance.
     */
    @Test
    fun `a truncated array yields only the hands that are actually present`() {
        val data = wire(handFloats(0f, 0f), handFloats(1f, 0f)).copyOf(1 + FLOATS_PER_HAND + 10)
        val result = parseHandLandmarkWire(data, 0L)
        assertEquals(1, result?.hands?.size, "the complete hand survives, the partial one is dropped")
    }

    @Test
    fun `a count larger than the payload does not overrun`() {
        val data = floatArrayOf(5f) + handFloats(0f, 0f)
        assertEquals(1, parseHandLandmarkWire(data, 0L)?.hands?.size)
    }

    @Test
    fun `a negative count is treated as no hands`() {
        assertNull(parseHandLandmarkWire(floatArrayOf(-3f), 0L))
    }
}
