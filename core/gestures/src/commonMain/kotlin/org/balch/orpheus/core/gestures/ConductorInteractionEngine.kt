package org.balch.orpheus.core.gestures

import kotlin.math.abs
import kotlin.math.sqrt

sealed interface ConductorEvent {
    // Individual voice gating (each finger = 1 voice)
    data class VoiceGateOn(val voiceIndex: Int) : ConductorEvent
    data class VoiceGateOff(val voiceIndex: Int) : ConductorEvent

    // Per-voice bend (single voice gated in a duo)
    data class VoiceBendSet(val voiceIndex: Int, val bendAmount: Float) : ConductorEvent
    data class VoiceRelease(val voiceIndex: Int) : ConductorEvent

    // Duo bend (both voices in duo gated simultaneously)
    data class DuoBendSet(val duoIndex: Int, val bendAmount: Float) : ConductorEvent
    data class DuoRelease(val duoIndex: Int) : ConductorEvent

    // Roll-routed controls
    data class BendSet(val value: Float) : ConductorEvent
    data class HoldSet(val quadIndex: Int, val value: Float) : ConductorEvent

    // Continuous per-hand controls
    data class DynamicsSet(val quadIndex: Int, val value: Float) : ConductorEvent
    data class TimbreSet(val value: Float) : ConductorEvent
}

/**
 * Maestro Mode interaction engine (v3 — individual voices).
 *
 * All four fingers per hand gate individual voices:
 *   Left:  Index=V0, Middle=V1, Ring=V2, Pinky=V3
 *   Right: Index=V4, Middle=V5, Ring=V6, Pinky=V7
 *
 * When both voices of a duo are gated simultaneously (index+middle or ring+pinky
 * on the same hand), they share a duo-level string bend with spring-back animation.
 * A single gated voice gets a solo bend.
 *
 * Hold control: Thumbs Up / Thumbs Down ASL signs activate Z-velocity fling
 * through hold detents [0, 0.4, 0.5, 0.6, 0.75] for the quad of that hand.
 *
 * Roll angle always routes to global pitch bend (no more ring modifier).
 * Palm Y → quad dynamics (auto-calibrated).
 */
class ConductorInteractionEngine {

    // Per-voice gating state (8 voices)
    private val voiceGated = BooleanArray(NUM_VOICES)

    // Per-voice thumb X at gate-on (for bend delta)
    private val thumbXAtGate = FloatArray(NUM_VOICES)

    // Track which voices are addressed this frame (for hand-disappearing detection)
    private val voicesAddressed = BooleanArray(NUM_VOICES)

    /** True when any voice is gated (used externally for swipe suppression). */
    val isAnyVoiceGated: Boolean get() = voiceGated.any { it }

    // Smoothed roll-derived values per hand
    private val smoothedBend = FloatArray(2)

    // Detent hold system per hand (driven by thumbs up/down)
    private val holdTarget = FloatArray(2)
    private val holdCurrent = FloatArray(2)
    private val lastEmittedHold = FloatArray(2) { -1f }
    private val holdSettled = BooleanArray(2) { true }
    private val lastRawSize = FloatArray(2)
    private val hasLastRawSize = BooleanArray(2)
    private val smoothedVelocity = FloatArray(2)
    private val holdActive = BooleanArray(2) // true when thumbs up/down is being shown

    // Auto-calibration for palmY → dynamics
    private var palmYMin = 0.3f
    private var palmYMax = 0.7f

    fun update(
        gestures: List<GestureState>,
        timestampMs: Long,
    ): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()
        voicesAddressed.fill(false)

        for (hand in gestures) {
            val isLeft = hand.handedness == Handedness.LEFT
            val handIdx = if (isLeft) 0 else 1
            val voiceOffset = if (isLeft) 0 else 4
            val quadIndex = if (isLeft) 0 else 1

            val thumbTip = hand.fingers.firstOrNull { it.finger == Finger.THUMB }
                ?: continue

            val refDist = hand.apparentSize.coerceAtLeast(MIN_APPARENT_SIZE)

            // -- Thumbs Up/Down: hold fling --
            val isThumbsGesture = hand.aslSign == AslSign.THUMBS_UP ||
                hand.aslSign == AslSign.THUMBS_DOWN
            val wasHoldActive = holdActive[handIdx]

            if (isThumbsGesture && hand.aslConfidence >= THUMBS_CONFIDENCE_MIN) {
                if (!wasHoldActive) {
                    // Entering hold mode — gate off any voices this hand had active
                    holdActive[handIdx] = true
                    hasLastRawSize[handIdx] = false
                    smoothedVelocity[handIdx] = 0f
                    events += gateOffVoicesForHand(voiceOffset)
                }
                events += processHoldFling(hand, handIdx, quadIndex, refDist)
                // Skip finger gating when showing thumbs gesture
                continue
            } else if (wasHoldActive) {
                // Exiting hold mode — settle to nearest detent
                holdActive[handIdx] = false
                if (!holdSettled[handIdx]) {
                    holdTarget[handIdx] = nearestDetent(holdTarget[handIdx])
                    holdCurrent[handIdx] = holdTarget[handIdx]
                    holdSettled[handIdx] = true
                    if (abs(holdCurrent[handIdx] - lastEmittedHold[handIdx]) > HOLD_EMIT_EPSILON) {
                        lastEmittedHold[handIdx] = holdCurrent[handIdx]
                        events += ConductorEvent.HoldSet(quadIndex, holdCurrent[handIdx])
                    }
                }
                hasLastRawSize[handIdx] = false
                smoothedVelocity[handIdx] = 0f
            }

            // -- All 4 fingers: individual voice gating + bend --
            for ((fingerIdx, finger) in VOICE_FINGERS.withIndex()) {
                val fingerState = hand.fingers.firstOrNull { it.finger == finger }
                    ?: continue

                val voiceIndex = voiceOffset + fingerIdx
                voicesAddressed[voiceIndex] = true

                val dist = distance2D(thumbTip.tipX, thumbTip.tipY,
                    fingerState.tipX, fingerState.tipY)

                val threshold = refDist * TOUCH_RATIOS[fingerIdx]
                val offThreshold = threshold * HYSTERESIS_FACTOR

                if (!voiceGated[voiceIndex] && dist <= threshold) {
                    voiceGated[voiceIndex] = true
                    thumbXAtGate[voiceIndex] = thumbTip.tipX
                    events += ConductorEvent.VoiceGateOn(voiceIndex)
                } else if (voiceGated[voiceIndex] && dist > offThreshold) {
                    voiceGated[voiceIndex] = false
                    // Check if this was part of a duo bend
                    val duoIndex = voiceIndex / 2
                    val partnerVoice = if (voiceIndex % 2 == 0) voiceIndex + 1 else voiceIndex - 1
                    if (voiceGated.getOrElse(partnerVoice) { false }) {
                        // Partner still gated — transition from duo bend to solo bend for partner
                        events += ConductorEvent.DuoRelease(duoIndex)
                    } else {
                        events += ConductorEvent.VoiceRelease(voiceIndex)
                    }
                    events += ConductorEvent.VoiceGateOff(voiceIndex)
                }
            }

            // -- Bend: duo vs solo --
            // Process bends for gated voices, grouped by duo
            for (localDuo in 0..1) {
                val duoIndex = (voiceOffset / 2) + localDuo
                val v0 = voiceOffset + localDuo * 2
                val v1 = v0 + 1
                val v0Gated = voiceGated.getOrElse(v0) { false }
                val v1Gated = voiceGated.getOrElse(v1) { false }

                if (v0Gated && v1Gated) {
                    // Both gated — duo bend using average of finger offsets
                    val f0 = hand.fingers.firstOrNull { it.finger == VOICE_FINGERS[localDuo * 2] }
                    val f1 = hand.fingers.firstOrNull { it.finger == VOICE_FINGERS[localDuo * 2 + 1] }
                    if (f0 != null && f1 != null) {
                        val avgX = (f0.tipX + f1.tipX) / 2f
                        val avgAnchor = (thumbXAtGate[v0] + thumbXAtGate[v1]) / 2f
                        val bendDelta = avgX - avgAnchor
                        val bendRange = refDist * BEND_X_RATIO
                        val bendNormalized = (bendDelta / bendRange).coerceIn(-1f, 1f)
                        events += ConductorEvent.DuoBendSet(duoIndex, bendNormalized)
                    }
                } else if (v0Gated) {
                    val f0 = hand.fingers.firstOrNull { it.finger == VOICE_FINGERS[localDuo * 2] }
                    if (f0 != null) {
                        val bendDelta = f0.tipX - thumbXAtGate[v0]
                        val bendRange = refDist * BEND_X_RATIO
                        val bendNormalized = (bendDelta / bendRange).coerceIn(-1f, 1f)
                        events += ConductorEvent.VoiceBendSet(v0, bendNormalized)
                    }
                } else if (v1Gated) {
                    val f1 = hand.fingers.firstOrNull { it.finger == VOICE_FINGERS[localDuo * 2 + 1] }
                    if (f1 != null) {
                        val bendDelta = f1.tipX - thumbXAtGate[v1]
                        val bendRange = refDist * BEND_X_RATIO
                        val bendNormalized = (bendDelta / bendRange).coerceIn(-1f, 1f)
                        events += ConductorEvent.VoiceBendSet(v1, bendNormalized)
                    }
                }
            }

            // -- Roll angle: always global pitch bend --
            val rollAngle = hand.rollAngle
            val target = (rollAngle / ROLL_HALF_RANGE).coerceIn(-1f, 1f)
            smoothedBend[handIdx] += (target - smoothedBend[handIdx]) * ROLL_SMOOTHING
            events += ConductorEvent.BendSet(smoothedBend[handIdx])

            // -- Dynamics: auto-calibrated palmY --
            palmYMin = minOf(palmYMin, hand.palmY)
            palmYMax = maxOf(palmYMax, hand.palmY)
            val range = (palmYMax - palmYMin).coerceAtLeast(0.05f)
            val normalized = ((palmYMax - hand.palmY) / range).coerceIn(0f, 1f)
            events += ConductorEvent.DynamicsSet(quadIndex, normalized)
        }

        // Gate off voices whose hand disappeared
        for (handOffset in intArrayOf(0, 4)) {
            val isLeft = handOffset == 0
            val anyVoiceUnaddressed = (handOffset until handOffset + 4).any {
                voiceGated[it] && !voicesAddressed[it]
            }
            if (!anyVoiceUnaddressed) continue
            val handPresent = gestures.any {
                (it.handedness == Handedness.LEFT) == isLeft
            }
            if (!handPresent) {
                events += gateOffVoicesForHand(handOffset)
            }
        }

        return events
    }

    private fun processHoldFling(
        hand: GestureState,
        handIdx: Int,
        quadIndex: Int,
        refDist: Float,
    ): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()
        val rawSize = hand.apparentSize
        if (!hasLastRawSize[handIdx]) {
            lastRawSize[handIdx] = rawSize
            hasLastRawSize[handIdx] = true
        } else {
            val rawVelocity = (rawSize - lastRawSize[handIdx]) / refDist
            lastRawSize[handIdx] = rawSize
            smoothedVelocity[handIdx] += (rawVelocity - smoothedVelocity[handIdx]) * VELOCITY_SMOOTHING

            val absVelocity = abs(smoothedVelocity[handIdx])
            val threshold = if (holdSettled[handIdx]) Z_VELOCITY_BREAKOUT
                            else Z_VELOCITY_THRESHOLD

            if (absVelocity > threshold) {
                holdSettled[handIdx] = false
                val holdDelta = smoothedVelocity[handIdx] * Z_HOLD_SENSITIVITY
                holdTarget[handIdx] = (holdTarget[handIdx] + holdDelta)
                    .coerceIn(0f, HOLD_DETENTS.last())
            } else if (!holdSettled[handIdx]) {
                holdTarget[handIdx] = nearestDetent(holdTarget[handIdx])
                holdCurrent[handIdx] = holdTarget[handIdx]
                holdSettled[handIdx] = true
            }
        }

        if (!holdSettled[handIdx]) {
            val distance = holdTarget[handIdx] - holdCurrent[handIdx]
            val smoothing = (abs(distance) * HOLD_SPEED_SCALE)
                .coerceIn(HOLD_MIN_SPEED, HOLD_MAX_SPEED)
            holdCurrent[handIdx] += distance * smoothing
        }
        if (abs(holdCurrent[handIdx] - lastEmittedHold[handIdx]) > HOLD_EMIT_EPSILON) {
            lastEmittedHold[handIdx] = holdCurrent[handIdx]
            events += ConductorEvent.HoldSet(quadIndex, holdCurrent[handIdx])
        }
        return events
    }

    /**
     * Gate off all voices belonging to one hand (4 voices starting at [voiceOffset]).
     * Correctly emits DuoRelease when both partners are gated, avoiding duplicates.
     */
    private fun gateOffVoicesForHand(voiceOffset: Int): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()
        val duosReleased = mutableSetOf<Int>()
        for (vi in voiceOffset until voiceOffset + 4) {
            if (voiceGated[vi]) {
                val duoIndex = vi / 2
                val partnerVoice = if (vi % 2 == 0) vi + 1 else vi - 1
                if (voiceGated.getOrElse(partnerVoice) { false } && duoIndex !in duosReleased) {
                    events += ConductorEvent.DuoRelease(duoIndex)
                    duosReleased += duoIndex
                } else if (!voiceGated.getOrElse(partnerVoice) { false }) {
                    events += ConductorEvent.VoiceRelease(vi)
                }
                events += ConductorEvent.VoiceGateOff(vi)
                voiceGated[vi] = false
            }
        }
        return events
    }

    fun reset(): List<ConductorEvent> {
        val events = mutableListOf<ConductorEvent>()
        val duosReleased = mutableSetOf<Int>()
        for (vi in 0 until NUM_VOICES) {
            if (voiceGated[vi]) {
                val duoIndex = vi / 2
                val partnerVoice = if (vi % 2 == 0) vi + 1 else vi - 1
                if (voiceGated.getOrElse(partnerVoice) { false } && duoIndex !in duosReleased) {
                    events += ConductorEvent.DuoRelease(duoIndex)
                    duosReleased += duoIndex
                } else if (!voiceGated.getOrElse(partnerVoice) { false }) {
                    events += ConductorEvent.VoiceRelease(vi)
                }
                events += ConductorEvent.VoiceGateOff(vi)
            }
            voiceGated[vi] = false
        }
        smoothedBend[0] = 0f; smoothedBend[1] = 0f
        holdTarget[0] = 0f; holdTarget[1] = 0f
        holdCurrent[0] = 0f; holdCurrent[1] = 0f
        lastEmittedHold[0] = -1f; lastEmittedHold[1] = -1f
        holdSettled[0] = true; holdSettled[1] = true
        hasLastRawSize[0] = false; hasLastRawSize[1] = false
        smoothedVelocity[0] = 0f; smoothedVelocity[1] = 0f
        holdActive[0] = false; holdActive[1] = false
        palmYMin = 0.3f; palmYMax = 0.7f
        return events
    }

    companion object {
        const val NUM_VOICES = 8

        /** All four gating fingers in order: index, middle, ring, pinky. */
        private val VOICE_FINGERS = arrayOf(Finger.INDEX, Finger.MIDDLE, Finger.RING, Finger.PINKY)

        private const val MIN_APPARENT_SIZE = 0.05f

        /** Touch threshold ratios (x apparentSize) per finger.
         *  Index/middle use standard threshold; ring is tighter; pinky is looser. */
        private val TOUCH_RATIOS = floatArrayOf(0.36f, 0.36f, 0.30f, 0.55f)

        private const val HYSTERESIS_FACTOR = 1.5f
        private const val ROLL_SMOOTHING = 0.08f
        private const val BEND_X_RATIO = 0.68f
        private const val ROLL_HALF_RANGE = 0.5f

        /** Minimum ASL confidence to recognize thumbs up/down for hold control. */
        private const val THUMBS_CONFIDENCE_MIN = 0.7f

        // ── Hold detent system ──
        val HOLD_DETENTS = floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 0.75f)
        private const val HOLD_EMIT_EPSILON = 0.002f
        private const val VELOCITY_SMOOTHING = 0.4f
        private const val Z_VELOCITY_THRESHOLD = 0.02f
        private const val Z_VELOCITY_BREAKOUT = 0.05f
        private const val Z_HOLD_SENSITIVITY = 4.0f
        private const val HOLD_MIN_SPEED = 0.08f
        private const val HOLD_MAX_SPEED = 0.25f
        private const val HOLD_SPEED_SCALE = 0.5f

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

        /** Duo index for a given voice. */
        fun duoForVoice(voiceIndex: Int): Int = voiceIndex / 2

        /** Quad index for a given voice. */
        fun quadForVoice(voiceIndex: Int): Int = voiceIndex / 4

        private fun distance2D(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }
    }
}
