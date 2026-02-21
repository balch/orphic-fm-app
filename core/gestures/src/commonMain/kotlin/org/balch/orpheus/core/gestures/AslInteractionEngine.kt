package org.balch.orpheus.core.gestures

import kotlin.math.abs

enum class SwipeDirection { LEFT, RIGHT }

enum class InteractionPhase { IDLE, SELECTED, CONTROLLING }

sealed interface AslEvent {
    data class TargetSelected(val sign: AslSign) : AslEvent
    data class TargetDeselected(val sign: AslSign) : AslEvent
    data class VoiceGateOn(val voiceIndex: Int) : AslEvent
    data class VoiceGateOff(val voiceIndex: Int) : AslEvent
    data class ParameterAdjust(val paramSign: AslSign, val delta: Float) : AslEvent
    data class EnvSpeedAdjust(val deltaZ: Float) : AslEvent
    data class SystemParamSet(val sign: AslSign, val value: Float) : AslEvent
    data class HoldToggle(val voiceIndex: Int) : AslEvent
    data class HoldOff(val voiceIndex: Int) : AslEvent
    data class PanelSwipe(val direction: SwipeDirection) : AslEvent
    data class DuoSelected(val duoIndex: Int) : AslEvent
    data class QuadSelected(val quadIndex: Int) : AslEvent
    data object ToggleConductorMode : AslEvent
}

class AslInteractionEngine(
    private val confidenceThreshold: Float = 0.7f,
    private val debounceFrames: Int = 3,
) {
    var phase: InteractionPhase = InteractionPhase.IDLE
        private set

    // Current selection state — exposed read-only for UI
    var selectedTarget: AslSign? = null
        private set
    var selectedParam: AslSign? = null
        private set
    var modePrefix: AslSign? = null  // D or Q prefix awaiting number
        private set

    // Remote adjust mode — R sign on pincher hand suppresses gate events
    var remoteAdjustArmed: Boolean = false
        private set

    // Pinch tracking for relative adjustment
    private var wasPinching = false
    private var pinchAnchorY: Float? = null
    private var lastPinchY: Float = 0.5f
    private var lastApparentSize: Float = 0f

    // Swipe detection: open-hand horizontal velocity
    private var swipePrevX: Float? = null
    private var swipeCooldownUntilMs: Long = 0L

    // Double-pinch detection for hold toggle
    private var lastPinchReleaseTimeMs: Long = -10_000L
    private val doublePinchWindowMs = 400L

    // Debounce: require N consecutive frames of same sign
    private var pendingSign: AslSign? = null
    private var pendingFrames: Int = 0
    private var confirmedSign: AslSign? = null

    fun update(
        gestures: List<GestureState>,
        timestampMs: Long = 0L,
    ): List<AslEvent> {
        val events = mutableListOf<AslEvent>()

        // Separate hands into signers and pinchers
        val signerHand = gestures.firstOrNull {
            it.aslSign != null && it.aslConfidence >= confidenceThreshold
        }
        val pincherHand = gestures.firstOrNull { it.isPinching && it != signerHand }

        // Check for R sign on any non-signer hand (remote adjust modifier)
        val remoteHand = gestures.firstOrNull {
            it != signerHand && it.aslSign == AslSign.LETTER_R
                && it.aslConfidence >= confidenceThreshold
        }
        if (remoteHand != null) remoteAdjustArmed = true

        // Debounce sign detection
        val rawSign = signerHand?.aslSign
        val stableSign = debounce(rawSign)

        // Process sign changes
        if (stableSign != confirmedSign) {
            confirmedSign?.let { old ->
                events += processSignExit(old)
            }
            confirmedSign = stableSign
            stableSign?.let { sign ->
                events += processSignEnter(sign)
            }
        }

        // Process pinch interactions
        if (pincherHand != null) {
            events += processPinch(pincherHand, timestampMs)
        } else if (wasPinching) {
            events += processPinchRelease(timestampMs)
        }

        // Swipe detection: fully open hand with fast horizontal motion
        val swipeHand = gestures.firstOrNull {
            !it.isPinching && it.handOpenness > SWIPE_OPENNESS_MIN && it.fingerCount >= 4
        }
        if (swipeHand != null) {
            events += processSwipe(swipeHand, timestampMs)
        } else {
            resetSwipe()
        }

        // Update phase
        phase = when {
            confirmedSign == null && !wasPinching -> InteractionPhase.IDLE
            pincherHand != null -> InteractionPhase.CONTROLLING
            confirmedSign != null -> InteractionPhase.SELECTED
            else -> InteractionPhase.IDLE
        }

        wasPinching = pincherHand != null
        return events
    }

    /**
     * Swipe-only check — usable from any gesture mode (ASL or Conductor).
     * Returns any [AslEvent.PanelSwipe] events detected from open-hand swat motion.
     */
    fun checkSwipe(gestures: List<GestureState>, timestampMs: Long): List<AslEvent> {
        // Require fully open hand (all 4 non-thumb fingers extended) so conductor
        // finger-to-thumb gestures (pinky hold, ring tap, string gating) don't trigger swipe.
        val swipeHand = gestures.firstOrNull {
            !it.isPinching && it.handOpenness > SWIPE_OPENNESS_MIN && it.fingerCount >= 4
        }
        return if (swipeHand != null) {
            processSwipe(swipeHand, timestampMs)
        } else {
            resetSwipe()
            emptyList()
        }
    }

    fun reset() {
        phase = InteractionPhase.IDLE
        selectedTarget = null
        selectedParam = null
        modePrefix = null
        remoteAdjustArmed = false
        wasPinching = false
        pinchAnchorY = null
        confirmedSign = null
        pendingSign = null
        pendingFrames = 0
        resetSwipe()
    }

    private fun debounce(sign: AslSign?): AslSign? {
        if (sign == pendingSign) {
            pendingFrames++
        } else {
            pendingSign = sign
            pendingFrames = 1
        }
        return if (pendingFrames >= debounceFrames) sign else confirmedSign
    }

    private fun processSignEnter(sign: AslSign): List<AslEvent> {
        val events = mutableListOf<AslEvent>()
        when (sign.category) {
            AslCategory.NUMBER -> {
                if (modePrefix == AslSign.LETTER_D) {
                    val duoIndex = sign.voiceIndex() ?: return events
                    if (duoIndex < 4) {
                        events += AslEvent.DuoSelected(duoIndex)
                        selectedTarget = sign
                    }
                    modePrefix = null
                } else if (modePrefix == AslSign.LETTER_Q) {
                    val quadIndex = sign.voiceIndex() ?: return events
                    if (quadIndex < 2) {
                        events += AslEvent.QuadSelected(quadIndex)
                        selectedTarget = sign
                    }
                    modePrefix = null
                } else {
                    selectedTarget = sign
                    events += AslEvent.TargetSelected(sign)
                }
            }
            AslCategory.MODE -> {
                modePrefix = sign
            }
            AslCategory.PARAMETER -> {
                selectedParam = sign
            }
            AslCategory.SYSTEM -> {
                selectedTarget = sign
                events += AslEvent.TargetSelected(sign)
            }
            AslCategory.COMMAND -> {
                when (sign) {
                    AslSign.LETTER_A -> {
                        selectedTarget?.let { events += AslEvent.TargetDeselected(it) }
                        selectedTarget = null
                        selectedParam = null
                        modePrefix = null
                    }
                    AslSign.THUMBS_UP -> {
                        // Thumbs-up is now used for swipe panel switching.
                        // Swipe detection handled in update() via processSwipe().
                    }
                    AslSign.THUMBS_DOWN -> {
                        selectedTarget?.voiceIndex()?.let { vi ->
                            events += AslEvent.HoldOff(vi) // hold off
                        }
                    }
                    AslSign.ILY -> {
                        events += AslEvent.ToggleConductorMode
                    }
                    else -> {}
                }
            }
        }
        return events
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processSignExit(sign: AslSign): List<AslEvent> = emptyList()

    private fun processPinch(hand: GestureState, @Suppress("UNUSED_PARAMETER") timestampMs: Long): List<AslEvent> {
        val events = mutableListOf<AslEvent>()
        val target = selectedTarget ?: return events

        if (!wasPinching) {
            pinchAnchorY = hand.palmY
            lastPinchY = hand.palmY
            lastApparentSize = hand.apparentSize
            // Gate voice on immediately when pinch starts (unless remote adjust is armed)
            if (!remoteAdjustArmed) {
                target.voiceIndex()?.let { vi ->
                    events += AslEvent.VoiceGateOn(vi)
                }
            }
        } else {
            val currentY = hand.palmY
            val deltaY = lastPinchY - currentY  // up = positive delta
            lastPinchY = currentY

            // Z-axis via apparent hand size: hand closer to camera = bigger = positive delta
            val currentSize = hand.apparentSize
            val deltaZ = currentSize - lastApparentSize  // toward camera = positive
            lastApparentSize = currentSize

            if (selectedParam != null && abs(deltaY) > 0.001f) {
                events += AslEvent.ParameterAdjust(selectedParam!!, deltaY)
            }
            // EnvSpeed from depth (apparent size), active during any pinch-drag with a target
            if (abs(deltaZ) > 0.0005f) {
                events += AslEvent.EnvSpeedAdjust(deltaZ)
            }
        }
        return events
    }

    /**
     * Detect fast horizontal palm motion (open-hand swat).
     * Compares per-frame X delta against velocity threshold.
     */
    private fun processSwipe(hand: GestureState, timestampMs: Long): List<AslEvent> {
        if (timestampMs < swipeCooldownUntilMs) {
            swipePrevX = hand.palmX
            return emptyList()
        }

        val prev = swipePrevX
        swipePrevX = hand.palmX
        if (prev == null) return emptyList()

        val deltaX = hand.palmX - prev
        if (abs(deltaX) >= SWIPE_VELOCITY_THRESHOLD) {
            val direction = if (deltaX > 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
            swipeCooldownUntilMs = timestampMs + SWIPE_COOLDOWN_MS
            return listOf(AslEvent.PanelSwipe(direction))
        }

        return emptyList()
    }

    private fun resetSwipe() {
        swipePrevX = null
    }

    private fun processPinchRelease(timestampMs: Long): List<AslEvent> {
        val events = mutableListOf<AslEvent>()
        pinchAnchorY = null

        if (remoteAdjustArmed) {
            remoteAdjustArmed = false  // consumed on release
        } else {
            val target = selectedTarget
            target?.voiceIndex()?.let { vi ->
                val isDoublePinch = timestampMs - lastPinchReleaseTimeMs < doublePinchWindowMs
                if (isDoublePinch) {
                    // Double-pinch = toggle hold (voice stays gated via hold)
                    events += AslEvent.HoldToggle(vi)
                } else {
                    // Single pinch release = gate off
                    events += AslEvent.VoiceGateOff(vi)
                }
                lastPinchReleaseTimeMs = timestampMs
            }
        }
        return events
    }

    companion object {
        /** Minimum per-frame palm X delta to register as a swipe (normalized 0-1). */
        private const val SWIPE_VELOCITY_THRESHOLD = 0.04f
        /** Minimum hand openness (0=fist, 1=spread) to be eligible for swipe. */
        private const val SWIPE_OPENNESS_MIN = 0.6f
        /** Cooldown between swipe events to prevent rapid-fire. */
        private const val SWIPE_COOLDOWN_MS = 600L
    }
}
