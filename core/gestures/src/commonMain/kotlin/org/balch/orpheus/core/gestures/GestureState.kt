package org.balch.orpheus.core.gestures

/**
 * Interpreted gesture state derived from raw hand landmarks.
 * All values are normalized for direct use as control signals.
 * This is source-agnostic — works with any 21-point hand landmark provider.
 */
data class GestureState(
    val isPinching: Boolean,
    val pinchStrength: Float,
    val palmX: Float,
    val palmY: Float,
    /**
     * Apparent hand size: wrist-to-middle-MCP distance in normalized coords.
     * Larger = hand closer to camera. More reliable than raw Z for depth tracking.
     */
    val apparentSize: Float = 0f,
    val rollAngle: Float,
    val ringFingerDirection: Float,
    val handedness: Handedness,
    val fingers: List<FingerState> = emptyList(),
    val pinch: PinchState = PinchState(false, 0.5f, 0.5f, 0f),
    val handOpenness: Float = 1f,
    /** Number of extended fingers (INDEX through PINKY, excluding thumb). */
    val fingerCount: Int = 0,
    /** Recognized ASL sign from GestureRecognizer, null if none or below threshold. */
    val aslSign: AslSign? = null,
    /** Confidence score (0-1) for the recognized ASL sign. */
    val aslConfidence: Float = 0f,
)
