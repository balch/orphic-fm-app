package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AslSignClassifierTest {

    private val classifier = AslSignClassifier()
    private val interpreter = GestureInterpreter()

    /**
     * Build a realistic hand pose from finger tip positions.
     * MCP joints are placed at a fixed baseline; tips above MCP = extended,
     * tips below MCP = curled. Wrist is below all MCPs.
     */
    private fun hand(
        thumbTip: Pair<Float, Float>,
        indexTip: Pair<Float, Float>,
        middleTip: Pair<Float, Float>,
        ringTip: Pair<Float, Float>,
        pinkyTip: Pair<Float, Float>,
        handedness: Handedness = Handedness.RIGHT,
    ): Pair<List<HandLandmark>, List<FingerState>> {
        // Build landmarks with realistic joint chain positions
        val wrist = HandLandmark(0.5f, 0.85f, 0f)
        // MCPs across the palm
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

        val landmarks = listOf(
            wrist,                                                      // 0
            thumbCmc, thumbMcp, thumbIp,                                // 1-3
            HandLandmark(thumbTip.first, thumbTip.second, 0f),          // 4
            indexMcp, indexPip, indexDip,                                // 5-7
            HandLandmark(indexTip.first, indexTip.second, 0f),          // 8
            middleMcp, middlePip, middleDip,                            // 9-11
            HandLandmark(middleTip.first, middleTip.second, 0f),       // 12
            ringMcp, ringPip, ringDip,                                  // 13-15
            HandLandmark(ringTip.first, ringTip.second, 0f),           // 16
            pinkyMcp, pinkyPip, pinkyDip,                               // 17-19
            HandLandmark(pinkyTip.first, pinkyTip.second, 0f),         // 20
        )

        // Use the real interpreter to get finger states (matches production path)
        val state = interpreter.interpret(landmarks, handedness)
        return landmarks to state.fingers
    }

    private fun classify(
        thumbTip: Pair<Float, Float>,
        indexTip: Pair<Float, Float>,
        middleTip: Pair<Float, Float>,
        ringTip: Pair<Float, Float>,
        pinkyTip: Pair<Float, Float>,
        handedness: Handedness = Handedness.RIGHT,
    ): Pair<AslSign?, Float> {
        val (landmarks, fingers) = hand(
            thumbTip, indexTip, middleTip, ringTip, pinkyTip, handedness,
        )
        return classifier.classify(landmarks, fingers, handedness)
    }

    // Tips above MCP = extended (lower Y = higher in frame)
    // Tips at/below MCP = curled (higher Y)
    private val up = 0.20f       // extended finger tip Y
    private val down = 0.70f     // curled finger tip Y
    private val thumbOut = 0.15f // thumb extended X (away from palm)
    private val thumbIn = 0.45f  // thumb curled X (near palm)

    @Test
    fun `NUM_1 - only index extended`() {
        val (sign, conf) = classify(
            thumbTip = 0.30f to 0.80f, // thumb tucked down away from curled fingers
            indexTip = 0.40f to up,
            middleTip = 0.50f to down,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.NUM_1, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `NUM_2 - index and middle extended close together`() {
        val (sign, _) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.44f to up,
            middleTip = 0.46f to up,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        // Could be V or 2 depending on spread — both are acceptable
        assertTrue(sign == AslSign.NUM_2 || sign == AslSign.LETTER_V)
    }

    @Test
    fun `LETTER_R - index and middle extended and crossed (right hand)`() {
        // R sign: index + middle extended, crossed (index tipX > middle tipX for right hand)
        // Ring + pinky curled, thumb not extended
        val (sign, conf) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.55f to up,   // index tip crosses past middle (x > middle tipX)
            middleTip = 0.45f to up,  // middle tip to the left of index
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.LETTER_R, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `R requires minimum crossing distance - slight cross is V or 2`() {
        // Index barely past middle (within hysteresis band) — should NOT classify as R
        val (sign, _) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.505f to up,   // barely crosses middle at 0.50
            middleTip = 0.50f to up,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertTrue(
            sign == AslSign.NUM_2 || sign == AslSign.LETTER_V,
            "Slight crossing should be V or 2, got $sign"
        )
    }

    /**
     * Build a horizontal hand pose for the H sign.
     * Unlike the standard hand() helper (which models a vertical hand),
     * this places the wrist to the left and fingers extending rightward,
     * so all landmarks share a similar Y — mimicking a hand held sideways.
     */
    private fun horizontalHand(): Pair<List<HandLandmark>, List<FingerState>> {
        val y = 0.50f  // shared baseline Y for the horizontal hand
        val wrist = HandLandmark(0.15f, y, 0f)

        // Thumb — curled: tip closer to palm center than IP joint
        // palmCenterX = (wrist.x + middleMcp.x) / 2 = (0.15 + 0.30) / 2 = 0.225
        val thumbCmc = HandLandmark(0.20f, y - 0.08f, 0f)
        val thumbMcp = HandLandmark(0.22f, y - 0.12f, 0f)
        val thumbIp  = HandLandmark(0.26f, y - 0.10f, 0f)  // IP farther from palm center
        val thumbTip = HandLandmark(0.22f, y - 0.06f, 0f)  // tip closer to palm center

        // Index — extended rightward, tip slightly above PIP so isExtended = true
        val indexMcp = HandLandmark(0.30f, y - 0.02f, 0f)
        val indexPip = HandLandmark(0.45f, y + 0.01f, 0f)
        val indexDip = HandLandmark(0.55f, y - 0.02f, 0f)
        val indexTip = HandLandmark(0.65f, y - 0.03f, 0f)

        // Middle — extended rightward, close to index, tip above PIP
        val middleMcp = HandLandmark(0.30f, y + 0.04f, 0f)
        val middlePip = HandLandmark(0.45f, y + 0.06f, 0f)
        val middleDip = HandLandmark(0.55f, y + 0.02f, 0f)
        val middleTip = HandLandmark(0.65f, y - 0.02f, 0f)

        // Ring — curled
        val ringMcp = HandLandmark(0.30f, y + 0.08f, 0f)
        val ringPip = HandLandmark(0.35f, y + 0.10f, 0f)
        val ringDip = HandLandmark(0.32f, y + 0.12f, 0f)
        val ringTip = HandLandmark(0.30f, y + 0.14f, 0f)

        // Pinky — curled
        val pinkyMcp = HandLandmark(0.30f, y + 0.14f, 0f)
        val pinkyPip = HandLandmark(0.34f, y + 0.16f, 0f)
        val pinkyDip = HandLandmark(0.32f, y + 0.18f, 0f)
        val pinkyTip = HandLandmark(0.30f, y + 0.20f, 0f)

        val landmarks = listOf(
            wrist,
            thumbCmc, thumbMcp, thumbIp, thumbTip,
            indexMcp, indexPip, indexDip, indexTip,
            middleMcp, middlePip, middleDip, middleTip,
            ringMcp, ringPip, ringDip, ringTip,
            pinkyMcp, pinkyPip, pinkyDip, pinkyTip,
        )
        val state = interpreter.interpret(landmarks, Handedness.RIGHT)
        return landmarks to state.fingers
    }

    @Test
    fun `LETTER_H - index and middle horizontal together`() {
        // H sign: index + middle extended horizontally (tips near wrist Y)
        val (landmarks, fingers) = horizontalHand()
        val (sign, conf) = classifier.classify(landmarks, fingers, Handedness.RIGHT)
        assertEquals(AslSign.LETTER_H, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `LETTER_H - recognized with slightly tilted hand`() {
        val y = 0.50f
        val wrist = HandLandmark(0.15f, y, 0f)

        val thumbCmc = HandLandmark(0.20f, y - 0.08f, 0f)
        val thumbMcp = HandLandmark(0.22f, y - 0.12f, 0f)
        val thumbIp  = HandLandmark(0.26f, y - 0.10f, 0f)
        val thumbTip = HandLandmark(0.22f, y - 0.06f, 0f)

        val indexMcp = HandLandmark(0.30f, y - 0.02f, 0f)
        val indexPip = HandLandmark(0.45f, y + 0.01f, 0f)
        val indexDip = HandLandmark(0.55f, y - 0.04f, 0f)
        val indexTip = HandLandmark(0.65f, y - 0.08f, 0f)

        val middleMcp = HandLandmark(0.30f, y + 0.04f, 0f)
        val middlePip = HandLandmark(0.45f, y + 0.06f, 0f)
        val middleDip = HandLandmark(0.55f, y, 0f)
        val middleTip = HandLandmark(0.65f, y - 0.06f, 0f)

        val ringMcp = HandLandmark(0.30f, y + 0.08f, 0f)
        val ringPip = HandLandmark(0.35f, y + 0.10f, 0f)
        val ringDip = HandLandmark(0.32f, y + 0.12f, 0f)
        val ringTip = HandLandmark(0.30f, y + 0.14f, 0f)

        val pinkyMcp = HandLandmark(0.30f, y + 0.14f, 0f)
        val pinkyPip = HandLandmark(0.34f, y + 0.16f, 0f)
        val pinkyDip = HandLandmark(0.32f, y + 0.18f, 0f)
        val pinkyTip = HandLandmark(0.30f, y + 0.20f, 0f)

        val landmarks = listOf(
            wrist,
            thumbCmc, thumbMcp, thumbIp, thumbTip,
            indexMcp, indexPip, indexDip, indexTip,
            middleMcp, middlePip, middleDip, middleTip,
            ringMcp, ringPip, ringDip, ringTip,
            pinkyMcp, pinkyPip, pinkyDip, pinkyTip,
        )
        val state = interpreter.interpret(landmarks, Handedness.RIGHT)
        val (sign, conf) = classifier.classify(landmarks, state.fingers, Handedness.RIGHT)
        assertEquals(AslSign.LETTER_H, sign, "Slightly tilted horizontal hand should still be H")
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `NUM_2 vertical does not match H`() {
        // Same finger pattern (index + middle together) but pointing UP (vertical)
        val (sign, _) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.44f to up,
            middleTip = 0.46f to up,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertTrue(sign == AslSign.NUM_2 || sign == AslSign.LETTER_V, "Expected V or 2, got $sign")
    }

    @Test
    fun `LETTER_V - index and middle extended with spread`() {
        val (sign, conf) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.35f to up,
            middleTip = 0.55f to up,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.LETTER_V, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `NUM_3 - thumb index middle extended`() {
        val (sign, conf) = classify(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.NUM_3, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `NUM_4 - four fingers extended, thumb curled`() {
        val (sign, conf) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        assertEquals(AslSign.NUM_4, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `NUM_5 - all five extended`() {
        val (sign, conf) = classify(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        assertEquals(AslSign.NUM_5, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `LETTER_W - index middle ring extended`() {
        val (sign, conf) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.LETTER_W, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `ILY sign — thumb, index, pinky extended, middle and ring curled`() {
        val (landmarks, fingers) = hand(
            thumbTip = 0.25f to 0.40f,
            indexTip = 0.40f to 0.20f,
            middleTip = 0.50f to 0.65f,
            ringTip = 0.60f to 0.68f,
            pinkyTip = 0.70f to 0.25f,
        )
        val (sign, confidence) = classifier.classify(landmarks, fingers, Handedness.RIGHT)
        assertEquals(AslSign.ILY, sign, "Should recognize ILY sign")
        assertTrue(confidence >= 0.75f)
    }

    @Test
    fun `LETTER_Y - thumb and pinky extended`() {
        val (sign, conf) = classify(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to down,
            middleTip = 0.50f to down,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to up,
        )
        assertEquals(AslSign.LETTER_Y, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `LETTER_L - thumb and index extended in L shape`() {
        val (sign, conf) = classify(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to down,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        assertEquals(AslSign.LETTER_L, sign)
        assertTrue(conf >= 0.75f)
    }

    @Test
    fun `LETTER_A - fist (all curled)`() {
        val (sign, _) = classify(
            thumbTip = thumbIn to 0.65f,
            indexTip = 0.40f to down,
            middleTip = 0.50f to down,
            ringTip = 0.60f to down,
            pinkyTip = 0.70f to down,
        )
        // Could be A, S, or M — all are fist variants
        assertNotNull(sign)
        assertTrue(
            sign == AslSign.LETTER_A || sign == AslSign.LETTER_S || sign == AslSign.LETTER_M,
            "Expected fist sign (A/S/M) but got $sign"
        )
    }

    @Test
    fun `classifier returns confidence above threshold`() {
        val (sign, conf) = classify(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        assertNotNull(sign)
        assertTrue(conf >= 0.75f, "Expected confidence >= 0.75 but was $conf")
    }

    @Test
    fun `interpreter uses classifier when no native gesture name`() {
        // NUM_5 pose: all fingers extended
        val (landmarks, _) = hand(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        val state = interpreter.interpret(landmarks, Handedness.RIGHT)
        assertEquals(AslSign.NUM_5, state.aslSign)
        assertTrue(state.aslConfidence > 0f)
    }

    @Test
    fun `interpreter prefers native when confident for known signs`() {
        val (landmarks, _) = hand(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        // Pose looks like NUM_5, native says "1" at high confidence — native wins for stability
        val state = interpreter.interpret(landmarks, Handedness.RIGHT, "1", 0.95f)
        assertEquals(AslSign.NUM_1, state.aslSign)
    }

    @Test
    fun `interpreter uses rule-based for ILY even when native disagrees`() {
        val (landmarks, _) = hand(
            thumbTip = 0.25f to 0.40f,
            indexTip = 0.40f to 0.20f,
            middleTip = 0.50f to 0.65f,
            ringTip = 0.60f to 0.68f,
            pinkyTip = 0.70f to 0.25f,
        )
        // ILY pose — native says "C" but rule-based detects ILY (not in native vocabulary)
        val state = interpreter.interpret(landmarks, Handedness.RIGHT, "C", 0.90f)
        assertEquals(AslSign.ILY, state.aslSign)
    }

    @Test
    fun `interpreter boosts confidence when native and rule agree`() {
        val (landmarks, _) = hand(
            thumbTip = thumbOut to 0.45f,
            indexTip = 0.40f to up,
            middleTip = 0.50f to up,
            ringTip = 0.60f to up,
            pinkyTip = 0.70f to up,
        )
        // Pose looks like NUM_5, native also says "5" — both agree
        val state = interpreter.interpret(landmarks, Handedness.RIGHT, "5", 0.95f)
        assertEquals(AslSign.NUM_5, state.aslSign)
        assertTrue(state.aslConfidence >= 0.9f)
    }
}
