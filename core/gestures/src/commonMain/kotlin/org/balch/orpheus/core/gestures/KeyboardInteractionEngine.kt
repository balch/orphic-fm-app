package org.balch.orpheus.core.gestures

import kotlin.math.abs
import kotlin.math.min
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Events emitted by [KeyboardInteractionEngine].
 */
sealed interface KeyboardEvent {
    data class NoteOn(val keyIndex: Int, val velocity: Float) : KeyboardEvent
    data class NoteOff(val keyIndex: Int) : KeyboardEvent
    data class CycleEngine(val direction: Int) : KeyboardEvent
}

/**
 * Keyboard mode interaction engine.
 *
 * Maps fingertip positions to a 12-key chromatic keyboard along the bottom
 * of the camera frame. Each non-thumb finger is tracked independently so
 * multiple simultaneous notes are possible.
 *
 * The keyboard zone uses Y-axis hysteresis to prevent flutter:
 * - Enter at Y >= [ZONE_Y_ENTER]
 * - Exit at Y < [ZONE_Y_EXIT]
 */
class KeyboardInteractionEngine {

    private val lock = SynchronizedObject()

    /**
     * Per-finger tracking state keyed by (Handedness, Finger).
     */
    private data class FingerKey(val hand: Handedness, val finger: Finger)

    private data class ActiveNote(
        val keyIndex: Int,
        val inZone: Boolean,
    )

    /** Active notes keyed by finger identity. */
    private val activeNotes = mutableMapOf<FingerKey, ActiveNote>()

    /** Previous Y position per finger, for velocity calculation. */
    private val prevY = mutableMapOf<FingerKey, Float>()

    /** Set of finger keys addressed in the current frame. */
    private val addressedThisFrame = mutableSetOf<FingerKey>()

    /**
     * Process a frame of gesture data and return keyboard events.
     *
     * @param hands gesture states for all visible hands
     * @param frameTimeMs monotonic frame timestamp (unused currently, reserved for velocity smoothing)
     */
    fun update(hands: List<GestureState>, frameTimeMs: Long): List<KeyboardEvent> = synchronized(lock) {
        val events = mutableListOf<KeyboardEvent>()
        addressedThisFrame.clear()

        for (hand in hands) {
            for (fingerState in hand.fingers) {
                if (fingerState.finger == Finger.THUMB) continue

                val key = FingerKey(hand.handedness, fingerState.finger)
                addressedThisFrame.add(key)

                val x = fingerState.tipX
                val y = fingerState.tipY
                val existing = activeNotes[key]

                if (existing != null && existing.inZone) {
                    // Finger was in zone last frame — use hysteresis for Y exit
                    if (y < ZONE_Y_EXIT) {
                        // Left zone (above top edge, past hysteresis)
                        events.add(KeyboardEvent.NoteOff(existing.keyIndex))
                        activeNotes.remove(key)
                    } else if (y <= ZONE_Y_MAX && isInXBounds(x, y.coerceAtLeast(ZONE_Y_ENTER))) {
                        // Still in zone — check for key change (only when Y is in the main zone)
                        if (y >= ZONE_Y_ENTER) {
                            val newKey = xyToKeyIndex(x, y)
                            if (newKey != existing.keyIndex) {
                                events.add(KeyboardEvent.NoteOff(existing.keyIndex))
                                val velocity = computeVelocity(key, y)
                                events.add(KeyboardEvent.NoteOn(newKey, velocity))
                                activeNotes[key] = ActiveNote(newKey, inZone = true)
                            }
                        }
                        // In hysteresis band (ZONE_Y_EXIT <= y < ZONE_Y_ENTER): keep current key
                    } else {
                        // Moved outside X bounds or below zone bottom
                        events.add(KeyboardEvent.NoteOff(existing.keyIndex))
                        activeNotes.remove(key)
                    }
                } else {
                    // Finger was not in zone (or is new)
                    if (isInKeyboardBounds(x, y)) {
                        val keyIndex = xyToKeyIndex(x, y)
                        val velocity = computeVelocity(key, y)
                        events.add(KeyboardEvent.NoteOn(keyIndex, velocity))
                        activeNotes[key] = ActiveNote(keyIndex, inZone = true)
                    }
                }

                prevY[key] = y
            }
        }

        // Gate off notes for fingers that disappeared (hand removed from frame)
        val disappeared = activeNotes.keys - addressedThisFrame
        for (fingerKey in disappeared) {
            val note = activeNotes[fingerKey] ?: continue
            if (note.inZone) {
                events.add(KeyboardEvent.NoteOff(note.keyIndex))
            }
        }
        for (fingerKey in disappeared) {
            activeNotes.remove(fingerKey)
            prevY.remove(fingerKey)
        }

        events
    }

    /**
     * Reset all state, gating off any active notes.
     */
    fun reset(): List<KeyboardEvent> = synchronized(lock) {
        val events = mutableListOf<KeyboardEvent>()
        for ((_, note) in activeNotes) {
            if (note.inZone) {
                events.add(KeyboardEvent.NoteOff(note.keyIndex))
            }
        }
        activeNotes.clear()
        prevY.clear()
        addressedThisFrame.clear()
        events
    }

    private fun computeVelocity(key: FingerKey, currentY: Float): Float {
        val prev = prevY[key]
        if (prev == null) {
            // First frame for this finger — use a default velocity
            return DEFAULT_VELOCITY
        }
        val delta = abs(currentY - prev)
        // Map delta to 0..1 range. Faster crossing = higher velocity.
        return min(delta / MAX_VELOCITY_DELTA, 1f).coerceAtLeast(MIN_VELOCITY)
    }

    companion object {
        const val NUM_KEYS = 12
        const val KEY_X_MIN = 0.08f
        const val KEY_X_MAX = 0.92f
        const val ZONE_Y_ENTER = 0.40f
        const val ZONE_Y_EXIT = 0.36f
        const val ZONE_Y_MAX = 0.55f

        /** Perspective narrowing ratio — shared with KeyboardOverlay for visual alignment. */
        const val PERSPECTIVE_RATIO = 0.55f

        private const val DEFAULT_VELOCITY = 0.8f
        private const val MIN_VELOCITY = 0.1f
        private const val MAX_VELOCITY_DELTA = 0.5f

        /**
         * Map a normalized (x, y) coordinate to a key index (0..11),
         * accounting for perspective narrowing at the top of the zone.
         *
         * At ZONE_Y_MAX (bottom/near), keys span KEY_X_MIN..KEY_X_MAX.
         * At ZONE_Y_ENTER (top/far), keys span a narrower centered range.
         * The X range is linearly interpolated based on Y position.
         */
        fun xyToKeyIndex(x: Float, y: Float): Int {
            // How far through the zone (0 = top/far, 1 = bottom/near)
            val yFraction = ((y - ZONE_Y_ENTER) / (ZONE_Y_MAX - ZONE_Y_ENTER)).coerceIn(0f, 1f)

            // Interpolate X range: narrow at top, full at bottom
            val bottomWidth = KEY_X_MAX - KEY_X_MIN
            val topWidth = bottomWidth * PERSPECTIVE_RATIO
            val currentWidth = topWidth + (bottomWidth - topWidth) * yFraction
            val currentLeft = KEY_X_MIN + (bottomWidth - currentWidth) / 2f

            val fraction = ((x - currentLeft) / currentWidth).coerceIn(0f, 1f)
            return (fraction * NUM_KEYS).toInt().coerceIn(0, NUM_KEYS - 1)
        }

        /**
         * Check if an (x, y) point is within the perspective keyboard bounds.
         */
        fun isInKeyboardBounds(x: Float, y: Float): Boolean {
            if (y < ZONE_Y_ENTER || y > ZONE_Y_MAX) return false
            return isInXBounds(x, y)
        }

        /**
         * Check if X is within the perspective-narrowed horizontal range at the given Y.
         * Y must already be in the valid zone range.
         */
        internal fun isInXBounds(x: Float, y: Float): Boolean {
            val yFraction = ((y - ZONE_Y_ENTER) / (ZONE_Y_MAX - ZONE_Y_ENTER)).coerceIn(0f, 1f)
            val bottomWidth = KEY_X_MAX - KEY_X_MIN
            val topWidth = bottomWidth * PERSPECTIVE_RATIO
            val currentWidth = topWidth + (bottomWidth - topWidth) * yFraction
            val currentLeft = KEY_X_MIN + (bottomWidth - currentWidth) / 2f
            return x in currentLeft..(currentLeft + currentWidth)
        }
    }
}
