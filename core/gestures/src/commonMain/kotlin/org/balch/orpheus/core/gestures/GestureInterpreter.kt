package org.balch.orpheus.core.gestures

import com.diamondedge.logging.logging
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Interprets raw 21-point hand landmarks into gesture state.
 * Pure Kotlin — no platform or ML framework dependency.
 */
class GestureInterpreter(
    private val pinchThreshold: Float = 0.08f,
    private val maxPinchDistance: Float = 0.3f,
    private val pressZThreshold: Float = -0.1f,
    private val aslClassifier: AslSignClassifier = AslSignClassifier(),
) {
    private val log = logging("GestureInterpreter")

    fun interpret(
        landmarks: List<HandLandmark>,
        handedness: Handedness,
        gestureName: String? = null,
        gestureConfidence: Float = 0f,
    ): GestureState {
        require(landmarks.size == 21) { "Expected 21 landmarks, got ${landmarks.size}" }

        val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]
        val indexMcp = landmarks[LandmarkIndex.INDEX_MCP]
        val pinkyMcp = landmarks[LandmarkIndex.PINKY_MCP]
        val ringMcp = landmarks[LandmarkIndex.RING_MCP]

        // Pinch: distance between thumb tip and index tip
        val pinchDist = distance(thumbTip, indexTip)
        val isPinching = pinchDist < pinchThreshold
        val pinchStrength = (1f - (pinchDist / maxPinchDistance).coerceIn(0f, 1f))

        // Pinch midpoint
        val pinchMidX = (thumbTip.x + indexTip.x) / 2f
        val pinchMidY = (thumbTip.y + indexTip.y) / 2f

        // Palm position: midpoint of wrist and middle finger MCP
        val palmX = (wrist.x + middleMcp.x) / 2f
        val palmY = (wrist.y + middleMcp.y) / 2f
        // Apparent hand size: distance from wrist to middle MCP.
        // When hand moves toward camera, this grows; away, it shrinks.
        val apparentSize = distance(wrist, middleMcp)

        // Hand openness: average fingertip distance from palm center,
        // normalized by wrist-to-middle-MCP reference length.
        // 0.0 = tight fist, 1.0 = fully spread hand.
        val palmCenterX = palmX
        val palmCenterY = palmY
        val refDist = distance(wrist, middleMcp)
        val handOpenness = if (refDist > 0.001f) {
            val avgTipDist = LandmarkIndex.FINGERTIPS.map { idx ->
                val tip = landmarks[idx]
                sqrt((tip.x - palmCenterX).let { it * it } + (tip.y - palmCenterY).let { it * it })
            }.average().toFloat()
            (avgTipDist / refDist).coerceIn(0f, 1f)
        } else {
            0.5f
        }

        // Roll: angle from index MCP to pinky MCP (with y-axis pointing up)
        val rollAngle = atan2(
            indexMcp.y - pinkyMcp.y,
            pinkyMcp.x - indexMcp.x,
        )

        // Ring finger direction: wrist to ring MCP vector angle
        val ringDirection = atan2(
            ringMcp.y - wrist.y,
            ringMcp.x - wrist.x,
        )

        // Per-fingertip states with extension detection
        val fingers = Finger.entries.map { finger ->
            val tip = landmarks[finger.tipIndex]
            val pip = landmarks[finger.pipIndex]
            val isExtended = if (finger == Finger.THUMB) {
                // Thumb extends laterally: tip farther from palm center than IP joint
                val tipDx = kotlin.math.abs(tip.x - palmCenterX)
                val pipDx = kotlin.math.abs(pip.x - palmCenterX)
                tipDx > pipDx
            } else {
                // Other fingers: tip above PIP joint (lower Y = higher in frame)
                tip.y < pip.y
            }
            FingerState(
                finger = finger,
                tipX = tip.x,
                tipY = tip.y,
                tipZ = tip.z,
                isPressed = tip.z < pressZThreshold,
                isExtended = isExtended,
                handedness = handedness,
            )
        }

        // Count extended fingers excluding thumb (matches "1 finger" / "2 finger" intent)
        val fingerCount = fingers.count { it.finger != Finger.THUMB && it.isExtended }

        // ASL sign: fuse rule-based and native ML classifiers
        val ruleResult = aslClassifier.classify(landmarks, fingers, handedness)
        val nativeSign = gestureName?.let { AslSign.fromLabel(it) }
        val nativeConf = if (nativeSign != null) gestureConfidence else 0f

        val (aslSign, aslConfidence) = fuseClassifiers(ruleResult, nativeSign, nativeConf)

        return GestureState(
            isPinching = isPinching,
            pinchStrength = pinchStrength,
            palmX = palmX,
            palmY = palmY,
            apparentSize = apparentSize,
            rollAngle = rollAngle,
            ringFingerDirection = ringDirection,
            handedness = handedness,
            fingers = fingers,
            handOpenness = handOpenness,
            pinch = PinchState(
                isPinching = isPinching,
                midpointX = pinchMidX,
                midpointY = pinchMidY,
                strength = pinchStrength,
            ),
            fingerCount = fingerCount,
            aslSign = aslSign,
            aslConfidence = aslConfidence,
        )
    }

    companion object {
        /** Signs with geometric discriminators the ML model can't reliably learn. */
        private val geometricSigns = setOf(
            AslSign.LETTER_R,  // finger crossing
            AslSign.LETTER_H,  // hand horizontal
            AslSign.LETTER_D,  // thumb touching middle
            AslSign.LETTER_Q,  // pointing down
        )

        /** Signs the native GR was never trained on — rule-based always wins. */
        private val ruleOnlySigns = setOf(AslSign.ILY)

        private const val AGREEMENT_BOOST = 1.15f
        private const val DISAGREEMENT_PENALTY = 0.85f
    }

    private fun fuseClassifiers(
        ruleResult: Pair<AslSign?, Float>,
        nativeSign: AslSign?,
        nativeConf: Float,
    ): Pair<AslSign?, Float> {
        val (ruleSign, ruleConf) = ruleResult

        // 1. Rule-only signs always win
        if (ruleSign in ruleOnlySigns) {
            log.debug { "FUSE RULE_ONLY: $ruleSign rule=$ruleConf (ml=$nativeSign/$nativeConf)" }
            return ruleResult
        }

        // 2. Both classifiers agree on the same sign — boost confidence
        if (ruleSign != null && ruleSign == nativeSign) {
            val boosted = (maxOf(ruleConf, nativeConf) * AGREEMENT_BOOST).coerceAtMost(0.99f)
            log.debug { "FUSE AGREE: $ruleSign rule=$ruleConf ml=$nativeConf -> $boosted" }
            return ruleSign to boosted
        }

        // 3. Both fire but disagree
        if (ruleSign != null && nativeSign != null && nativeConf >= 0.5f) {
            // Geometric signs: trust rule-based (it checks features ML can't see)
            if (ruleSign in geometricSigns) {
                val penalized = ruleConf * DISAGREEMENT_PENALTY
                log.debug { "FUSE DISAGREE_GEO: rule=$ruleSign/$ruleConf ml=$nativeSign/$nativeConf -> $ruleSign/$penalized" }
                return ruleSign to penalized
            }
            // Non-geometric: trust higher confidence, penalized
            val winner = if (ruleConf >= nativeConf) ruleSign to ruleConf else nativeSign to nativeConf
            val penalized = winner.second * DISAGREEMENT_PENALTY
            log.debug { "FUSE DISAGREE: rule=$ruleSign/$ruleConf ml=$nativeSign/$nativeConf -> ${winner.first}/$penalized" }
            return winner.first to penalized
        }

        // 4. Only rule-based fires
        if (ruleSign != null) {
            log.debug { "FUSE SINGLE_RULE: $ruleSign/$ruleConf (ml=$nativeSign/$nativeConf)" }
            return ruleResult
        }

        // 5. Only ML fires
        if (nativeSign != null) {
            log.debug { "FUSE SINGLE_ML: $nativeSign/$nativeConf (rule=null)" }
            return nativeSign to nativeConf
        }

        return null to 0f
    }

    private fun distance(a: HandLandmark, b: HandLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
