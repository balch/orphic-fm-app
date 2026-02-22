package org.balch.orpheus.core.gestures

import kotlin.math.abs
import kotlin.math.sqrt

sealed interface ConductorEvent {
    // String duo gating (each string = 2 voices)
    data class StringGateOn(val stringIndex: Int) : ConductorEvent
    data class StringGateOff(val stringIndex: Int) : ConductorEvent
    data class StringBendSet(val stringIndex: Int, val bendAmount: Float) : ConductorEvent
    data class StringRelease(val stringIndex: Int) : ConductorEvent

    // Roll-routed controls
    data class BendSet(val value: Float) : ConductorEvent
    data class HoldSet(val quadIndex: Int, val value: Float) : ConductorEvent
    data class ModSourceCycle(val quadIndex: Int) : ConductorEvent
    data class ModSourceLevelSet(val quadIndex: Int, val value: Float) : ConductorEvent

    // Continuous per-hand controls
    data class DynamicsSet(val quadIndex: Int, val value: Float) : ConductorEvent
    data class TimbreSet(val value: Float) : ConductorEvent
}

/**
 * Maestro Mode interaction engine (v2).
 *
 * Four fingers per hand, each with a distinct role:
 * - **Index/Middle**: gate a string duo (2 voices each). Finger tipX delta
 *   from thumb drives per-string bend. Release triggers spring-back.
 * - **Ring**: modifier for roll → duo mod source level. Double-tap cycles
 *   mod source (OFF→LFO→FM→FLUX) for both duos on the quad.
 * - **Pinky**: modifier for roll → quad hold.
 *
 * Roll angle routes based on which modifier is active:
 *   pinky > ring > global pitch bend (default).
 *
 * Palm Y → quad dynamics (auto-calibrated). Hand openness → timbre.
 */
class ConductorInteractionEngine {

    // String gating state (4 strings)
    private val stringGated = BooleanArray(NUM_STRINGS)

    // Per-finger modifier state (ring/pinky per hand)
    private val ringTouching = BooleanArray(2) // [left, right]
    private val pinkyTouching = BooleanArray(2)

    /** True when any hand has pinky touching thumb (hold modifier active). */
    val isAnyPinkyTouching: Boolean get() = pinkyTouching[0] || pinkyTouching[1]
    /** True when any hand has ring touching thumb (mod-level modifier active). */
    val isAnyRingTouching: Boolean get() = ringTouching[0] || ringTouching[1]

    // Smoothed roll-derived values per hand
    private val smoothedBend = FloatArray(2)
    private val smoothedModLevel = FloatArray(2)

    // Detent hold system per hand
    private val holdTarget = FloatArray(2)       // target detent value
    private val holdCurrent = FloatArray(2)      // smoothed current hold output
    private val lastEmittedHold = FloatArray(2) { -1f } // last value sent, -1 = never
    private val holdSettled = BooleanArray(2) { true } // true when current == target (latched)
    private val lastRawSize = FloatArray(2)      // previous frame's raw apparentSize
    private val hasLastRawSize = BooleanArray(2) // whether lastRawSize is initialized
    private val smoothedVelocity = FloatArray(2) // low-pass filtered Z velocity

    // Ring double-tap detection per hand
    private val lastRingTapMs = LongArray(2) { -1000L }

    // Pinky double-tap detection per hand (resets hold to 0)
    private val lastPinkyTapMs = LongArray(2) { -1000L }

    // Per-string thumb X at gate-on (for bend delta)
    private val thumbXAtGate = FloatArray(NUM_STRINGS)

    // Auto-calibration for palmY → dynamics
    private var palmYMin = 0.3f
    private var palmYMax = 0.7f

    fun update(
        gestures: List<GestureState>,
        timestampMs: Long,
    ): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()

        // Track which strings are addressed this frame
        val stringsAddressed = BooleanArray(NUM_STRINGS)

        for (hand in gestures) {
            val isLeft = hand.handedness == Handedness.LEFT
            val handIdx = if (isLeft) 0 else 1
            val stringOffset = if (isLeft) 0 else 2
            val quadIndex = if (isLeft) 0 else 1

            val thumbTip = hand.fingers.firstOrNull { it.finger == Finger.THUMB }
                ?: continue

            // Reference distance for scaling all thresholds by camera distance
            val refDist = hand.apparentSize.coerceAtLeast(MIN_APPARENT_SIZE)

            // -- Index & Middle: string gating + bend --
            for ((fingerIdx, finger) in STRING_FINGERS.withIndex()) {
                val fingerState = hand.fingers.firstOrNull { it.finger == finger }
                    ?: continue

                val stringIndex = stringOffset + fingerIdx
                stringsAddressed[stringIndex] = true

                val dist = distance2D(thumbTip.tipX, thumbTip.tipY,
                    fingerState.tipX, fingerState.tipY)

                val threshold = refDist * TOUCH_RATIOS[fingerIdx]
                val offThreshold = threshold * HYSTERESIS_FACTOR

                if (!stringGated[stringIndex] && dist <= threshold) {
                    stringGated[stringIndex] = true
                    thumbXAtGate[stringIndex] = thumbTip.tipX
                    events += ConductorEvent.StringGateOn(stringIndex)
                } else if (stringGated[stringIndex] && dist > offThreshold) {
                    stringGated[stringIndex] = false
                    events += ConductorEvent.StringRelease(stringIndex)
                    events += ConductorEvent.StringGateOff(stringIndex)
                }

                // Per-string bend while gated
                if (stringGated[stringIndex]) {
                    val bendDelta = fingerState.tipX - thumbXAtGate[stringIndex]
                    val bendRange = refDist * BEND_X_RATIO
                    val bendNormalized = (bendDelta / bendRange).coerceIn(-1f, 1f)
                    events += ConductorEvent.StringBendSet(stringIndex, bendNormalized)
                }
            }

            // -- Ring: modifier + double-tap --
            val ringState = hand.fingers.firstOrNull { it.finger == Finger.RING }
            if (ringState != null) {
                val dist = distance2D(thumbTip.tipX, thumbTip.tipY,
                    ringState.tipX, ringState.tipY)
                val wasRingTouching = ringTouching[handIdx]
                val threshold = refDist * RING_TOUCH_RATIO
                val offThreshold = threshold * HYSTERESIS_FACTOR

                if (!wasRingTouching && dist <= threshold) {
                    ringTouching[handIdx] = true
                    // Double-tap detection
                    if (timestampMs - lastRingTapMs[handIdx] <= DOUBLE_TAP_MS) {
                        events += ConductorEvent.ModSourceCycle(quadIndex)
                        lastRingTapMs[handIdx] = -1000L // reset to prevent triple-tap
                    } else {
                        lastRingTapMs[handIdx] = timestampMs
                    }
                } else if (wasRingTouching && dist > offThreshold) {
                    ringTouching[handIdx] = false
                }
            }

            // -- Pinky: modifier + Z-velocity detent hold --
            val pinkyState = hand.fingers.firstOrNull { it.finger == Finger.PINKY }
            if (pinkyState != null) {
                val dist = distance2D(thumbTip.tipX, thumbTip.tipY,
                    pinkyState.tipX, pinkyState.tipY)
                val threshold = refDist * PINKY_TOUCH_RATIO
                val offThreshold = threshold * HYSTERESIS_FACTOR

                if (!pinkyTouching[handIdx] && dist <= threshold) {
                    pinkyTouching[handIdx] = true
                    // Double-tap detection: reset hold to 0
                    if (timestampMs - lastPinkyTapMs[handIdx] <= DOUBLE_TAP_MS) {
                        holdTarget[handIdx] = 0f
                        holdCurrent[handIdx] = 0f
                        holdSettled[handIdx] = true
                        lastEmittedHold[handIdx] = 0f
                        events += ConductorEvent.HoldSet(quadIndex, 0f)
                        lastPinkyTapMs[handIdx] = -1000L // prevent triple-tap
                    } else {
                        lastPinkyTapMs[handIdx] = timestampMs
                    }
                    // Anchor Z tracking on pinky gate-on
                    hasLastRawSize[handIdx] = false
                    smoothedVelocity[handIdx] = 0f
                } else if (pinkyTouching[handIdx] && dist > offThreshold) {
                    pinkyTouching[handIdx] = false
                    hasLastRawSize[handIdx] = false
                    smoothedVelocity[handIdx] = 0f
                }
            }

            // -- Pinky hold: Z velocity drives detent stepping --
            if (pinkyTouching[handIdx]) {
                val rawSize = hand.apparentSize
                if (!hasLastRawSize[handIdx]) {
                    lastRawSize[handIdx] = rawSize
                    hasLastRawSize[handIdx] = true
                } else {
                    // Raw frame-to-frame velocity, normalized by hand size for distance invariance
                    val rawVelocity = (rawSize - lastRawSize[handIdx]) / refDist
                    lastRawSize[handIdx] = rawSize
                    smoothedVelocity[handIdx] += (rawVelocity - smoothedVelocity[handIdx]) * VELOCITY_SMOOTHING

                    val absVelocity = abs(smoothedVelocity[handIdx])

                    // Require higher velocity to break out of settled state
                    val threshold = if (holdSettled[handIdx]) Z_VELOCITY_BREAKOUT
                                    else Z_VELOCITY_THRESHOLD

                    if (absVelocity > threshold) {
                        holdSettled[handIdx] = false
                        val holdDelta = smoothedVelocity[handIdx] * Z_HOLD_SENSITIVITY
                        holdTarget[handIdx] = (holdTarget[handIdx] + holdDelta)
                            .coerceIn(0f, HOLD_DETENTS.last())
                    } else if (!holdSettled[handIdx]) {
                        // Velocity dropped — snap to nearest detent and latch
                        holdTarget[handIdx] = nearestDetent(holdTarget[handIdx])
                        holdCurrent[handIdx] = holdTarget[handIdx]
                        holdSettled[handIdx] = true
                    }
                }

                // Smooth holdCurrent toward target while unsettled
                if (!holdSettled[handIdx]) {
                    val distance = holdTarget[handIdx] - holdCurrent[handIdx]
                    val smoothing = (abs(distance) * HOLD_SPEED_SCALE)
                        .coerceIn(HOLD_MIN_SPEED, HOLD_MAX_SPEED)
                    holdCurrent[handIdx] += distance * smoothing
                }
                // Emit on change
                if (abs(holdCurrent[handIdx] - lastEmittedHold[handIdx]) > HOLD_EMIT_EPSILON) {
                    lastEmittedHold[handIdx] = holdCurrent[handIdx]
                    events += ConductorEvent.HoldSet(quadIndex, holdCurrent[handIdx])
                }
            }

            // -- Roll angle routing (priority: ring > bend, pinky no longer uses roll) --
            val rollAngle = hand.rollAngle
            when {
                ringTouching[handIdx] -> {
                    val target = ((rollAngle + ROLL_HALF_RANGE) / ROLL_FULL_RANGE)
                        .coerceIn(0f, 1f)
                    smoothedModLevel[handIdx] += (target - smoothedModLevel[handIdx]) * ROLL_SMOOTHING
                    events += ConductorEvent.ModSourceLevelSet(quadIndex, smoothedModLevel[handIdx])
                }
                else -> {
                    val target = (rollAngle / ROLL_HALF_RANGE).coerceIn(-1f, 1f)
                    smoothedBend[handIdx] += (target - smoothedBend[handIdx]) * ROLL_SMOOTHING
                    events += ConductorEvent.BendSet(smoothedBend[handIdx])
                }
            }

            // -- Dynamics: auto-calibrated palmY --
            palmYMin = minOf(palmYMin, hand.palmY)
            palmYMax = maxOf(palmYMax, hand.palmY)
            val range = (palmYMax - palmYMin).coerceAtLeast(0.05f)
            val normalized = ((palmYMax - hand.palmY) / range).coerceIn(0f, 1f)
            events += ConductorEvent.DynamicsSet(quadIndex, normalized)

            // Timbre (hand openness → sharpness) removed: noisy handOpenness values
            // in the 0.7–1.0 range caused unmusical sounds.
        }

        // Gate off strings whose hand disappeared
        for (si in 0 until NUM_STRINGS) {
            if (stringGated[si] && !stringsAddressed[si]) {
                val isLeft = si < 2
                val handPresent = gestures.any {
                    (it.handedness == Handedness.LEFT) == isLeft
                }
                if (!handPresent) {
                    stringGated[si] = false
                    events += ConductorEvent.StringRelease(si)
                    events += ConductorEvent.StringGateOff(si)
                }
            }
        }

        return events
    }

    fun reset(): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()
        for (si in 0 until NUM_STRINGS) {
            if (stringGated[si]) {
                events += ConductorEvent.StringRelease(si)
                events += ConductorEvent.StringGateOff(si)
            }
            stringGated[si] = false
        }
        ringTouching[0] = false; ringTouching[1] = false
        pinkyTouching[0] = false; pinkyTouching[1] = false
        smoothedBend[0] = 0f; smoothedBend[1] = 0f
        smoothedModLevel[0] = 0f; smoothedModLevel[1] = 0f
        holdTarget[0] = 0f; holdTarget[1] = 0f
        holdCurrent[0] = 0f; holdCurrent[1] = 0f
        lastEmittedHold[0] = -1f; lastEmittedHold[1] = -1f
        holdSettled[0] = true; holdSettled[1] = true
        hasLastRawSize[0] = false; hasLastRawSize[1] = false
        smoothedVelocity[0] = 0f; smoothedVelocity[1] = 0f
        lastRingTapMs[0] = -1000L; lastRingTapMs[1] = -1000L
        lastPinkyTapMs[0] = -1000L; lastPinkyTapMs[1] = -1000L
        palmYMin = 0.3f; palmYMax = 0.7f
        return events
    }

    companion object {
        const val NUM_STRINGS = 4
        const val NUM_VOICES = 8

        /** String-gating fingers: index and middle. */
        private val STRING_FINGERS = arrayOf(Finger.INDEX, Finger.MIDDLE)

        /** Minimum apparentSize to prevent division by near-zero. */
        private const val MIN_APPARENT_SIZE = 0.05f

        /** Touch threshold ratios (× apparentSize) for index and middle. */
        private val TOUCH_RATIOS = floatArrayOf(0.36f, 0.36f)

        /** Touch threshold ratios for ring and pinky modifiers.
         *  Ring is tighter to prevent accidental engagement. */
        private const val RING_TOUCH_RATIO = 0.30f
        private const val PINKY_TOUCH_RATIO = 0.55f

        private const val HYSTERESIS_FACTOR = 1.5f
        private const val ROLL_SMOOTHING = 0.08f

        /** Bend X range ratio (× apparentSize). */
        private const val BEND_X_RATIO = 0.68f

        /** Roll angle range (radians). ±0.5 rad ≈ ±29°. */
        private const val ROLL_HALF_RANGE = 0.5f
        private const val ROLL_FULL_RANGE = 1.0f

        /** Double-tap window for ring finger mod source cycling. */
        private const val DOUBLE_TAP_MS = 400L

        // ── Hold detent system ──
        /** Target hold positions — dramatic stops for performance. */
        val HOLD_DETENTS = floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 0.75f)

        /** Only emit HoldSet when value changed by at least this much. */
        private const val HOLD_EMIT_EPSILON = 0.002f

        /** Low-pass filter for Z velocity to suppress camera noise. */
        private const val VELOCITY_SMOOTHING = 0.4f

        /** Normalized Z velocity below this is noise — ignored. */
        private const val Z_VELOCITY_THRESHOLD = 0.02f

        /** Normalized velocity needed to break out of a settled detent. */
        private const val Z_VELOCITY_BREAKOUT = 0.05f

        /** How much Z velocity translates to hold change. Lower = less sensitive. */
        private const val Z_HOLD_SENSITIVITY = 4.0f

        /** Smoothing speed range for hold interpolation. */
        private const val HOLD_MIN_SPEED = 0.08f
        private const val HOLD_MAX_SPEED = 0.25f
        private const val HOLD_SPEED_SCALE = 0.5f

        /** Find nearest detent position for a given hold value. */
        private fun nearestDetent(value: Float): Float {
            var best = HOLD_DETENTS[0]
            var bestDist = abs(value - best)
            for (d in HOLD_DETENTS) {
                val dist = abs(value - d)
                if (dist < bestDist) {
                    best = d
                    bestDist = dist
                }
            }
            return best
        }

        /** Voices for a given string index. */
        fun voicesForString(stringIndex: Int): Pair<Int, Int> =
            Pair(stringIndex * 2, stringIndex * 2 + 1)

        private fun distance2D(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }
    }
}
