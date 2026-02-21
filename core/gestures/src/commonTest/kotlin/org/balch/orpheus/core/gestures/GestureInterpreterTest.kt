package org.balch.orpheus.core.gestures

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureInterpreterTest {

    private val interpreter = GestureInterpreter()

    /** Build 21 landmarks with all at (0.5, 0.5, 0) by default. */
    private fun landmarks(
        vararg overrides: Pair<Int, HandLandmark>
    ): List<HandLandmark> {
        val base = List(21) { HandLandmark(0.5f, 0.5f, 0f) }
        return base.toMutableList().apply {
            overrides.forEach { (i, lm) -> this[i] = lm }
        }
    }

    @Test
    fun `pinch detected when thumb and index tips are close`() {
        val lms = landmarks(
            LandmarkIndex.THUMB_TIP to HandLandmark(0.5f, 0.5f, 0f),
            LandmarkIndex.INDEX_TIP to HandLandmark(0.52f, 0.52f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertTrue(state.isPinching)
        assertTrue(state.pinchStrength > 0.8f)
    }

    @Test
    fun `no pinch when thumb and index tips are far apart`() {
        val lms = landmarks(
            LandmarkIndex.THUMB_TIP to HandLandmark(0.1f, 0.1f, 0f),
            LandmarkIndex.INDEX_TIP to HandLandmark(0.9f, 0.9f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertFalse(state.isPinching)
        assertTrue(state.pinchStrength < 0.2f)
    }

    @Test
    fun `palm position is midpoint of wrist and middle MCP`() {
        val lms = landmarks(
            LandmarkIndex.WRIST to HandLandmark(0.2f, 0.3f, 0f),
            LandmarkIndex.MIDDLE_MCP to HandLandmark(0.8f, 0.7f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertEquals(0.5f, state.palmX, 0.01f)
        assertEquals(0.5f, state.palmY, 0.01f)
    }

    @Test
    fun `roll angle is zero when hand is horizontal`() {
        val lms = landmarks(
            LandmarkIndex.INDEX_MCP to HandLandmark(0.3f, 0.5f, 0f),
            LandmarkIndex.PINKY_MCP to HandLandmark(0.7f, 0.5f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertEquals(0f, state.rollAngle, 0.01f)
    }

    @Test
    fun `roll angle is approximately negative PI div 2 when hand is vertical`() {
        val lms = landmarks(
            LandmarkIndex.INDEX_MCP to HandLandmark(0.5f, 0.2f, 0f),
            LandmarkIndex.PINKY_MCP to HandLandmark(0.5f, 0.8f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        // atan2(0.2 - 0.8, 0.5 - 0.5) = atan2(-0.6, 0) = -PI/2
        assertEquals(-PI.toFloat() / 2f, state.rollAngle, 0.1f)
    }

    @Test
    fun `handedness is preserved`() {
        val lms = landmarks()
        assertEquals(Handedness.LEFT, interpreter.interpret(lms, Handedness.LEFT).handedness)
        assertEquals(Handedness.RIGHT, interpreter.interpret(lms, Handedness.RIGHT).handedness)
    }

    @Test
    fun `fingers list has 5 entries with correct finger IDs`() {
        val state = interpreter.interpret(landmarks(), Handedness.RIGHT)
        assertEquals(5, state.fingers.size)
        assertEquals(Finger.THUMB, state.fingers[0].finger)
        assertEquals(Finger.INDEX, state.fingers[1].finger)
        assertEquals(Finger.MIDDLE, state.fingers[2].finger)
        assertEquals(Finger.RING, state.fingers[3].finger)
        assertEquals(Finger.PINKY, state.fingers[4].finger)
    }

    @Test
    fun `finger tip positions match landmark coordinates`() {
        val lms = landmarks(
            LandmarkIndex.INDEX_TIP to HandLandmark(0.3f, 0.7f, -0.1f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        val indexFinger = state.fingers[1]
        assertEquals(0.3f, indexFinger.tipX, 0.001f)
        assertEquals(0.7f, indexFinger.tipY, 0.001f)
        assertEquals(-0.1f, indexFinger.tipZ, 0.001f)
    }

    @Test
    fun `finger is pressed when Z is below threshold`() {
        val lms = landmarks(
            LandmarkIndex.MIDDLE_TIP to HandLandmark(0.5f, 0.5f, -0.15f),
        )
        val pressInterpreter = GestureInterpreter(pressZThreshold = -0.1f)
        val state = pressInterpreter.interpret(lms, Handedness.RIGHT)
        assertTrue(state.fingers[2].isPressed)
    }

    @Test
    fun `finger is not pressed when Z is above threshold`() {
        val pressInterpreter = GestureInterpreter(pressZThreshold = -0.1f)
        val state = pressInterpreter.interpret(landmarks(), Handedness.RIGHT)
        assertFalse(state.fingers[2].isPressed)
    }

    @Test
    fun `hand openness is high when fingertips are spread from palm`() {
        val lms = landmarks(
            LandmarkIndex.WRIST to HandLandmark(0.5f, 0.8f, 0f),
            LandmarkIndex.MIDDLE_MCP to HandLandmark(0.5f, 0.5f, 0f),
            LandmarkIndex.THUMB_TIP to HandLandmark(0.2f, 0.3f, 0f),
            LandmarkIndex.INDEX_TIP to HandLandmark(0.35f, 0.2f, 0f),
            LandmarkIndex.MIDDLE_TIP to HandLandmark(0.5f, 0.15f, 0f),
            LandmarkIndex.RING_TIP to HandLandmark(0.65f, 0.2f, 0f),
            LandmarkIndex.PINKY_TIP to HandLandmark(0.8f, 0.3f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertTrue(state.handOpenness > 0.7f, "Expected handOpenness > 0.7 but was ${state.handOpenness}")
    }

    @Test
    fun `hand openness is low when fingertips are curled near palm`() {
        val lms = landmarks(
            LandmarkIndex.WRIST to HandLandmark(0.5f, 0.8f, 0f),
            LandmarkIndex.MIDDLE_MCP to HandLandmark(0.5f, 0.5f, 0f),
            LandmarkIndex.THUMB_TIP to HandLandmark(0.5f, 0.65f, 0f),
            LandmarkIndex.INDEX_TIP to HandLandmark(0.5f, 0.65f, 0f),
            LandmarkIndex.MIDDLE_TIP to HandLandmark(0.5f, 0.65f, 0f),
            LandmarkIndex.RING_TIP to HandLandmark(0.5f, 0.65f, 0f),
            LandmarkIndex.PINKY_TIP to HandLandmark(0.5f, 0.65f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertTrue(state.handOpenness < 0.3f, "Expected handOpenness < 0.3 but was ${state.handOpenness}")
    }

    @Test
    fun `hand openness is between 0 and 1`() {
        val state = interpreter.interpret(landmarks(), Handedness.RIGHT)
        assertTrue(state.handOpenness in 0f..1f, "Expected handOpenness in 0..1 but was ${state.handOpenness}")
    }

    @Test
    fun `pinch midpoint is average of thumb tip and index tip`() {
        val lms = landmarks(
            LandmarkIndex.THUMB_TIP to HandLandmark(0.3f, 0.4f, 0f),
            LandmarkIndex.INDEX_TIP to HandLandmark(0.32f, 0.42f, 0f),
        )
        val state = interpreter.interpret(lms, Handedness.RIGHT)
        assertTrue(state.pinch.isPinching)
        assertEquals(0.31f, state.pinch.midpointX, 0.01f)
        assertEquals(0.41f, state.pinch.midpointY, 0.01f)
    }

    // === Pose helpers for fusion tests ===

    private val up = 0.20f
    private val down = 0.70f
    private val thumbIn = 0.45f
    private val thumbOut = 0.15f

    private fun buildHandLandmarks(
        thumbTip: Pair<Float, Float>,
        indexTip: Pair<Float, Float>,
        middleTip: Pair<Float, Float>,
        ringTip: Pair<Float, Float>,
        pinkyTip: Pair<Float, Float>,
    ): List<HandLandmark> {
        val wrist = HandLandmark(0.5f, 0.85f, 0f)
        val thumbCmc = HandLandmark(0.35f, 0.75f, 0f)
        val thumbMcp = HandLandmark(0.30f, 0.65f, 0f)
        val thumbIp = HandLandmark(
            (thumbMcp.x + thumbTip.first) / 2f,
            (thumbMcp.y + thumbTip.second) / 2f, 0f,
        )
        val indexMcp = HandLandmark(0.40f, 0.55f, 0f)
        val indexPip = HandLandmark(
            indexMcp.x, (indexMcp.y + indexTip.second) / 2f, 0f,
        )
        val indexDip = HandLandmark(
            indexTip.first, (indexPip.y + indexTip.second) / 2f, 0f,
        )
        val middleMcp = HandLandmark(0.50f, 0.52f, 0f)
        val middlePip = HandLandmark(
            middleMcp.x, (middleMcp.y + middleTip.second) / 2f, 0f,
        )
        val middleDip = HandLandmark(
            middleTip.first, (middlePip.y + middleTip.second) / 2f, 0f,
        )
        val ringMcp = HandLandmark(0.60f, 0.55f, 0f)
        val ringPip = HandLandmark(
            ringMcp.x, (ringMcp.y + ringTip.second) / 2f, 0f,
        )
        val ringDip = HandLandmark(
            ringTip.first, (ringPip.y + ringTip.second) / 2f, 0f,
        )
        val pinkyMcp = HandLandmark(0.70f, 0.58f, 0f)
        val pinkyPip = HandLandmark(
            pinkyMcp.x, (pinkyMcp.y + pinkyTip.second) / 2f, 0f,
        )
        val pinkyDip = HandLandmark(
            pinkyTip.first, (pinkyPip.y + pinkyTip.second) / 2f, 0f,
        )
        return listOf(
            wrist,
            thumbCmc, thumbMcp, thumbIp,
            HandLandmark(thumbTip.first, thumbTip.second, 0f),
            indexMcp, indexPip, indexDip,
            HandLandmark(indexTip.first, indexTip.second, 0f),
            middleMcp, middlePip, middleDip,
            HandLandmark(middleTip.first, middleTip.second, 0f),
            ringMcp, ringPip, ringDip,
            HandLandmark(ringTip.first, ringTip.second, 0f),
            pinkyMcp, pinkyPip, pinkyDip,
            HandLandmark(pinkyTip.first, pinkyTip.second, 0f),
        )
    }

    private fun vPoseLandmarks() = buildHandLandmarks(
        thumbTip = thumbIn to 0.65f,
        indexTip = 0.35f to up,
        middleTip = 0.55f to up,
        ringTip = 0.60f to down,
        pinkyTip = 0.70f to down,
    )

    private fun rPoseLandmarks() = buildHandLandmarks(
        thumbTip = thumbIn to 0.65f,
        indexTip = 0.55f to up,   // crossed past middle
        middleTip = 0.45f to up,
        ringTip = 0.60f to down,
        pinkyTip = 0.70f to down,
    )

    private fun ilyPoseLandmarks() = buildHandLandmarks(
        thumbTip = 0.25f to 0.40f,
        indexTip = 0.40f to 0.20f,
        middleTip = 0.50f to 0.65f,
        ringTip = 0.60f to 0.68f,
        pinkyTip = 0.70f to 0.25f,
    )

    // === Fusion tests ===

    @Test
    fun `fusion boosts confidence when rule and ML agree`() {
        val lms = vPoseLandmarks()
        val state = interpreter.interpret(lms, Handedness.RIGHT, "V", 0.80f)
        assertEquals(AslSign.LETTER_V, state.aslSign)
        assertTrue(state.aslConfidence > 0.80f,
            "Expected boosted confidence > 0.80 but was ${state.aslConfidence}")
    }

    @Test
    fun `fusion trusts rule-based for geometric sign R when ML disagrees`() {
        val lms = rPoseLandmarks()
        val state = interpreter.interpret(lms, Handedness.RIGHT, "V", 0.80f)
        assertEquals(AslSign.LETTER_R, state.aslSign,
            "Rule-based R should win over ML's V for geometric signs")
    }

    @Test
    fun `fusion penalizes confidence when classifiers disagree`() {
        val lms = rPoseLandmarks()
        val state = interpreter.interpret(lms, Handedness.RIGHT, "V", 0.80f)
        assertTrue(state.aslConfidence < 0.80f,
            "Disagreement should penalize confidence, got ${state.aslConfidence}")
    }

    @Test
    fun `fusion passes through rule-only signs regardless of ML`() {
        val lms = ilyPoseLandmarks()
        val state = interpreter.interpret(lms, Handedness.RIGHT, "5", 0.95f)
        assertEquals(AslSign.ILY, state.aslSign)
    }
}
