package org.balch.orpheus.core.gestures

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Events emitted by the pad interaction engine.
 * Consumers (ViewModel) route these to the appropriate synth controls.
 */
sealed interface PadEvent {
    data class PadPressed(val pad: GesturePad, val finger: Finger, val handedness: Handedness, val x: Float, val y: Float) : PadEvent
    data class PadReleased(val pad: GesturePad, val finger: Finger, val handedness: Handedness) : PadEvent
    data class WobbleMove(val pad: GesturePad, val finger: Finger, val handedness: Handedness, val x: Float, val y: Float) : PadEvent
    data class DrumTrigger(val pad: GesturePad, val drumType: Int) : PadEvent
    data class PinchOnPad(val pad: GesturePad, val strength: Float) : PadEvent
    data class PinchBend(val midpointX: Float, val midpointY: Float, val strength: Float) : PadEvent
    data object PinchReleased : PadEvent
    data class ToggleHold(val voiceIndex: Int) : PadEvent
}

/** Composite key for tracking a finger from a specific hand. */
private data class HandedFinger(val handedness: Handedness, val finger: Finger)

/**
 * Maps per-fingertip states and pinch state against a pad layout to produce pad events.
 * Tracks which finger is assigned to which pad so presses/releases pair correctly.
 * Detects double-taps for hold toggling.
 *
 * Stateful — call [update] each frame with current finger states.
 */
class PadInteractionEngine(
    private var pads: List<GesturePad>,
    private val doubleTapWindowMs: Long = 300L,
) {
    private val lock = SynchronizedObject()
    private val activePresses = mutableMapOf<HandedFinger, GesturePad>()
    private val lastReleaseTime = mutableMapOf<String, Long>()
    private val lastReleaseFinger = mutableMapOf<String, HandedFinger>()
    private var wasPinching = false

    /** IDs of pads currently held down by any finger. */
    val activePadIds: Set<String> get() = synchronized(lock) {
        activePresses.values.mapTo(mutableSetOf()) { it.id }
    }

    fun update(
        fingers: List<FingerState>,
        pinch: PinchState,
        timestampMs: Long,
    ): List<PadEvent> = synchronized(lock) {
        val events = mutableListOf<PadEvent>()
        val pressedFingers = fingers.filter { it.isPressed }
            .associateBy { HandedFinger(it.handedness, it.finger) }
        val allFingers = fingers.associateBy { HandedFinger(it.handedness, it.finger) }

        // 1. Check for releases (finger was pressing a pad, now isn't pressed)
        val released = activePresses.entries.filter { (hf, _) -> hf !in pressedFingers }
        for ((hf, pad) in released) {
            activePresses.remove(hf)
            if (pad.type == PadType.VOICE) {
                // Double-tap detection — emit ToggleHold before PadReleased
                val lastRelease = lastReleaseTime[pad.id]
                val lastHf = lastReleaseFinger[pad.id]
                if (lastRelease != null && lastHf == hf &&
                    (timestampMs - lastRelease) < doubleTapWindowMs
                ) {
                    events += PadEvent.ToggleHold(pad.voiceIndex!!)
                    lastReleaseTime.remove(pad.id)
                    lastReleaseFinger.remove(pad.id)
                } else {
                    lastReleaseTime[pad.id] = timestampMs
                    lastReleaseFinger[pad.id] = hf
                }
                events += PadEvent.PadReleased(pad, hf.finger, hf.handedness)
            }
        }

        // 2. Check finger leaving pad zone while still pressed (voice and drum)
        val movedOut = activePresses.entries.filter { (hf, pad) ->
            val fs = allFingers[hf]
            fs != null && fs.isPressed && !pad.bounds.contains(fs.tipX, fs.tipY)
        }.toList() // snapshot to avoid concurrent modification
        for ((hf, pad) in movedOut) {
            activePresses.remove(hf)
            if (pad.type == PadType.VOICE) {
                events += PadEvent.PadReleased(pad, hf.finger, hf.handedness)
            }
            // Drums: just release from activePresses (no event needed, they're one-shot)
        }

        // 3. Check for new presses and wobble moves
        for ((hf, fingerState) in pressedFingers) {
            val currentPad = activePresses[hf]
            if (currentPad != null) {
                if (currentPad.type == PadType.VOICE) {
                    events += PadEvent.WobbleMove(currentPad, hf.finger, hf.handedness, fingerState.tipX, fingerState.tipY)
                }
            } else {
                val hitPad = pads.hitTest(fingerState.tipX, fingerState.tipY)
                if (hitPad != null) {
                    activePresses[hf] = hitPad
                    when (hitPad.type) {
                        PadType.VOICE -> events += PadEvent.PadPressed(hitPad, hf.finger, hf.handedness, fingerState.tipX, fingerState.tipY)
                        PadType.DRUM -> events += PadEvent.DrumTrigger(hitPad, hitPad.drumType!!)
                    }
                }
            }
        }

        // 4. Handle pinch
        if (pinch.isPinching) {
            val pinchPad = pads.hitTest(pinch.midpointX, pinch.midpointY)
            if (pinchPad != null && pinchPad.type == PadType.VOICE) {
                events += PadEvent.PinchOnPad(pinchPad, pinch.strength)
            } else {
                events += PadEvent.PinchBend(pinch.midpointX, pinch.midpointY, pinch.strength)
            }
        } else if (wasPinching) {
            events += PadEvent.PinchReleased
        }
        wasPinching = pinch.isPinching

        // 5. Clean up stale double-tap entries
        lastReleaseTime.entries.removeAll { (timestampMs - it.value) > doubleTapWindowMs }

        events
    }

    fun updatePads(newPads: List<GesturePad>) = synchronized(lock) {
        pads = newPads
        activePresses.clear()
    }
}
