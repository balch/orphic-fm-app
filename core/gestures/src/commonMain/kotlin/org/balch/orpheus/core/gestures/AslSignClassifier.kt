package org.balch.orpheus.core.gestures

import com.diamondedge.logging.logging
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rule-based ASL sign classifier using 21-point hand landmarks.
 *
 * Classifies static ASL finger-spelling signs from finger extension patterns,
 * thumb position, and inter-finger distances. Designed for the subset of signs
 * used by Orpheus gesture control (numbers 1-8, letters A/B/C/D/H/L/M/Q/S/V/W/Y).
 *
 * This replaces the native MediaPipe GestureRecognizer (whose C API crashes in
 * LIVE_STREAM mode) with a lightweight pure-Kotlin alternative.
 */
class AslSignClassifier(
    private val confidenceHigh: Float = 0.90f,
    private val confidenceMedium: Float = 0.75f,
) {
    private val log = logging("AslClassifier")

    /**
     * Classify an ASL sign from hand landmarks and pre-computed finger states.
     * Returns (sign, confidence) or (null, 0) if no sign is recognized.
     */
    fun classify(
        landmarks: List<HandLandmark>,
        fingers: List<FingerState>,
        handedness: Handedness,
    ): Pair<AslSign?, Float> {
        require(landmarks.size == 21) { "Expected 21 landmarks, got ${landmarks.size}" }
        require(fingers.size == 5) { "Expected 5 finger states, got ${fingers.size}" }

        val thumb = fingers[0]
        val index = fingers[1]
        val middle = fingers[2]
        val ring = fingers[3]
        val pinky = fingers[4]

        val extended = booleanArrayOf(
            thumb.isExtended, index.isExtended, middle.isExtended,
            ring.isExtended, pinky.isExtended,
        )
        val extCount = extended.count { it }

        // Compute additional geometric features beyond simple extension
        val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = landmarks[LandmarkIndex.INDEX_TIP]
        val middleTip = landmarks[LandmarkIndex.MIDDLE_TIP]
        val ringTip = landmarks[LandmarkIndex.RING_TIP]
        val pinkyTip = landmarks[LandmarkIndex.PINKY_TIP]
        val wrist = landmarks[LandmarkIndex.WRIST]
        val indexMcp = landmarks[LandmarkIndex.INDEX_MCP]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]
        val ringMcp = landmarks[LandmarkIndex.RING_MCP]
        val pinkyMcp = landmarks[LandmarkIndex.PINKY_MCP]
        val thumbIp = landmarks[LandmarkIndex.THUMB_IP]
        val indexPip = landmarks[LandmarkIndex.INDEX_PIP]
        val indexDip = landmarks[LandmarkIndex.INDEX_DIP]
        val middlePip = landmarks[LandmarkIndex.MIDDLE_PIP]
        val ringPip = landmarks[LandmarkIndex.RING_PIP]

        // Finger spread: horizontal distance between adjacent fingertips
        val indexMiddleSpread = abs(indexTip.x - middleTip.x)
        val middleRingSpread = abs(middleTip.x - ringTip.x)

        // Thumb-to-fingertip distances (for signs where thumb touches a finger)
        val thumbIndexDist = dist(thumbTip, indexTip)
        val thumbMiddleDist = dist(thumbTip, middleTip)
        val thumbRingDist = dist(thumbTip, ringTip)
        val thumbPinkyDist = dist(thumbTip, pinkyTip)

        // Reference distance: wrist to middle MCP (normalizes for hand size)
        val refDist = dist(wrist, middleMcp).coerceAtLeast(0.01f)

        // Normalized touch thresholds
        val touchThreshold = refDist * 0.35f

        // Thumb position relative to palm (is it across or alongside?)
        val thumbAcrossPalm = isThumbAcrossPalm(landmarks, handedness)

        // Finger curl: how much each finger is curled (tip below PIP)
        val indexCurled = indexTip.y > indexPip.y
        val middleCurled = middleTip.y > middlePip.y
        val ringCurled = ringTip.y > ringPip.y
        val pinkyCurled = pinkyTip.y > landmarks[LandmarkIndex.PINKY_PIP].y

        // Is the hand pointing downward? (fingertips below wrist)
        val pointingDown = indexTip.y > wrist.y && middleTip.y > wrist.y

        // Thumbs up/down: thumb must protrude above/below the fist.
        // In a regular fist, the thumb sits near the knuckles. In thumbs up,
        // it sticks above indexMcp. In thumbs down, below the wrist.
        val thumbAboveKnuckles = thumbTip.y < indexMcp.y  // thumb above the fist line
        val thumbBelowWrist = thumbTip.y > wrist.y + refDist * 0.1f  // thumb below wrist

        // Finger state summary for logging
        val fingerSummary = buildString {
            append(if (thumb.isExtended) "T" else "t")
            append(if (index.isExtended) "I" else "i")
            append(if (middle.isExtended) "M" else "m")
            append(if (ring.isExtended) "R" else "r")
            append(if (pinky.isExtended) "P" else "p")
        }

        // ILY diagnostic: track when 2+ of the 3 ILY fingers are extended
        val ilyFingers = listOf(thumb.isExtended, index.isExtended, pinky.isExtended).count { it }

        // Classify based on finger patterns.
        // Order matters: more specific patterns (e.g., D with thumb-touch)
        // must come before general ones (e.g., 1 = index only).
        val result: Pair<AslSign?, Float> = when {
            // === THUMBS UP: Thumb protrudes above fist, all fingers curled ===
            // Must precede A/S/M (which are also fist shapes).
            // Key: thumb tip above index MCP (above the knuckle line).
            indexCurled && middleCurled && ringCurled && pinkyCurled &&
                thumbAboveKnuckles ->
                AslSign.THUMBS_UP to confidenceHigh

            // === THUMBS DOWN: Thumb protrudes below wrist, all fingers curled ===
            indexCurled && middleCurled && ringCurled && pinkyCurled &&
                thumbBelowWrist ->
                AslSign.THUMBS_DOWN to confidenceHigh

            // === ILY: Thumb + index + pinky extended, middle curled ===
            // Ring finger may partially extend (biomechanically coupled to pinky).
            // Must precede Y (which is thumb + pinky only, no index).
            thumb.isExtended && index.isExtended && !middle.isExtended &&
                pinky.isExtended ->
                AslSign.ILY to confidenceHigh

            // === Y: Thumb + pinky extended, middle 3 curled ===
            thumb.isExtended && !index.isExtended && !middle.isExtended &&
                !ring.isExtended && pinky.isExtended ->
                AslSign.LETTER_Y to confidenceHigh

            // === D: Index up, thumb touching middle finger ===
            // Must precede NUM_1 and LETTER_L — D is more specific.
            index.isExtended && !middle.isExtended && !ring.isExtended &&
                !pinky.isExtended && thumbMiddleDist < touchThreshold ->
                AslSign.LETTER_D to confidenceMedium

            // === 1: Only index extended (thumb may be partially out) ===
            !thumb.isExtended && index.isExtended && !middle.isExtended &&
                !ring.isExtended && !pinky.isExtended ->
                AslSign.NUM_1 to confidenceHigh

            // === L: Index + thumb extended in L-shape, others curled ===
            thumb.isExtended && index.isExtended && !middle.isExtended &&
                !ring.isExtended && !pinky.isExtended ->
                AslSign.LETTER_L to confidenceHigh

            // === R: Index + middle extended and crossed, ring + pinky curled ===
            index.isExtended && middle.isExtended &&
                !ring.isExtended && !pinky.isExtended && !thumb.isExtended &&
                isFingersCrossed(indexTip, middleTip, handedness, refDist) ->
                AslSign.LETTER_R to confidenceMedium

            // === H: Index + middle horizontal, together (hold) ===
            // Same finger pattern as V/2 but hand is horizontal (tips near wrist Y).
            index.isExtended && middle.isExtended &&
                !ring.isExtended && !pinky.isExtended && !thumb.isExtended &&
                !isFingersCrossed(indexTip, middleTip, handedness, refDist) &&
                indexMiddleSpread <= refDist * 0.15f &&
                abs(indexTip.y - wrist.y) < refDist * 0.55f ->
                AslSign.LETTER_H to confidenceMedium

            // === V / 2: Index + middle extended ===
            // V has fingers spread, 2 has them together (we treat as same for now)
            index.isExtended && middle.isExtended &&
                !ring.isExtended && !pinky.isExtended && !thumb.isExtended ->
                if (indexMiddleSpread > refDist * 0.15f) {
                    AslSign.LETTER_V to confidenceHigh
                } else {
                    AslSign.NUM_2 to confidenceMedium
                }

            // === 3: Index + middle + thumb extended, ring + pinky curled ===
            thumb.isExtended && index.isExtended && middle.isExtended &&
                !ring.isExtended && !pinky.isExtended ->
                AslSign.NUM_3 to confidenceHigh

            // === W: Index + middle + ring extended (spread), thumb + pinky curled ===
            !thumb.isExtended && index.isExtended && middle.isExtended &&
                ring.isExtended && !pinky.isExtended ->
                AslSign.LETTER_W to confidenceHigh

            // === 4: All four fingers extended, thumb curled ===
            !thumb.isExtended && index.isExtended && middle.isExtended &&
                ring.isExtended && pinky.isExtended ->
                AslSign.NUM_4 to confidenceHigh

            // === 5: All five fingers extended and spread ===
            thumb.isExtended && index.isExtended && middle.isExtended &&
                ring.isExtended && pinky.isExtended ->
                AslSign.NUM_5 to confidenceHigh

            // === 8: Middle + thumb touching, index + ring + pinky extended ===
            index.isExtended && !middle.isExtended && ring.isExtended &&
                pinky.isExtended && thumbMiddleDist < touchThreshold ->
                AslSign.NUM_8 to confidenceMedium

            // === 7: Ring + thumb touching, index + middle + pinky extended ===
            index.isExtended && middle.isExtended && !ring.isExtended &&
                pinky.isExtended && thumbRingDist < touchThreshold ->
                AslSign.NUM_7 to confidenceMedium

            // === 6: Pinky + thumb touching, index + middle + ring extended ===
            index.isExtended && middle.isExtended && ring.isExtended &&
                !pinky.isExtended && thumbPinkyDist < touchThreshold ->
                AslSign.NUM_6 to confidenceMedium

            // === C: Curved hand — no fingers fully extended, thumb opposed ===
            // Hand makes a C-shape: all fingers partially curled, thumb away from palm
            !index.isExtended && !middle.isExtended && !ring.isExtended &&
                !pinky.isExtended && thumb.isExtended &&
                thumbIndexDist > touchThreshold ->
                AslSign.LETTER_C to confidenceMedium

            // === B: 4 fingers up, thumb across palm ===
            index.isExtended && middle.isExtended && ring.isExtended &&
                pinky.isExtended && thumbAcrossPalm ->
                AslSign.LETTER_B to confidenceMedium

            // === Q: Thumb + index pointing down, others curled ===
            pointingDown && !middle.isExtended && !ring.isExtended &&
                !pinky.isExtended ->
                AslSign.LETTER_Q to confidenceMedium

            // === M: Fist with thumb under 3 fingers ===
            // All fingers curled, thumb tucked under index/middle/ring
            extCount == 0 && thumbAcrossPalm && thumbTip.y < middleMcp.y ->
                AslSign.LETTER_M to confidenceMedium

            // === S: Fist with thumb over fingers ===
            extCount == 0 && !thumbAcrossPalm &&
                thumbTip.y < indexMcp.y ->
                AslSign.LETTER_S to confidenceMedium

            // === A: Fist with thumb alongside ===
            extCount == 0 ->
                AslSign.LETTER_A to confidenceMedium

            else -> {
                null to 0f
            }
        }

        // Permanent logging: always log detected sign with finger state
        val (sign, _) = result
        if (sign != null) {
            log.info { "ASL: $sign fingers=$fingerSummary" }
        } else if (ilyFingers >= 2) {
            // Near-ILY but didn't match: log diagnostic
            log.info {
                "ASL: NONE fingers=$fingerSummary (near-ILY: " +
                    "midCurled=$middleCurled thumbAbove=$thumbAboveKnuckles " +
                    "idxCurled=$indexCurled pinkyCurled=$pinkyCurled)"
            }
        }

        return result
    }

    /**
     * Check if index and middle fingers are crossed (index tip on the "wrong" side of middle tip).
     * For right hand: index tipX > middle tipX means crossed.
     * For left hand: index tipX < middle tipX means crossed.
     */
    private fun isFingersCrossed(
        indexTip: HandLandmark,
        middleTip: HandLandmark,
        handedness: Handedness,
        refDist: Float,
    ): Boolean {
        val minCrossing = refDist * 0.05f
        return if (handedness == Handedness.RIGHT) {
            (indexTip.x - middleTip.x) > minCrossing
        } else {
            (middleTip.x - indexTip.x) > minCrossing
        }
    }

    /**
     * Check if the thumb is positioned across the palm (for B, M, S distinction).
     * Thumb tip is between index MCP and pinky MCP horizontally.
     */
    private fun isThumbAcrossPalm(
        landmarks: List<HandLandmark>,
        handedness: Handedness,
    ): Boolean {
        val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
        val indexMcp = landmarks[LandmarkIndex.INDEX_MCP]
        val pinkyMcp = landmarks[LandmarkIndex.PINKY_MCP]

        // For right hand: thumb across palm means thumb tip is to the right of index MCP
        // For left hand: opposite direction
        return if (handedness == Handedness.RIGHT) {
            thumbTip.x > indexMcp.x && thumbTip.x < pinkyMcp.x
        } else {
            thumbTip.x < indexMcp.x && thumbTip.x > pinkyMcp.x
        }
    }

    private fun dist(a: HandLandmark, b: HandLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
