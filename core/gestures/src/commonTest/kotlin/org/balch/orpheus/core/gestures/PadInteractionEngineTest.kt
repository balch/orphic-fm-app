package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadInteractionEngineTest {

    private val voicePad0 = GesturePad("v0", PadType.VOICE, voiceIndex = 0, drumType = null,
        bounds = NormalizedRect(0f, 0f, 0.5f, 0.5f), label = "1")
    private val voicePad1 = GesturePad("v1", PadType.VOICE, voiceIndex = 1, drumType = null,
        bounds = NormalizedRect(0.5f, 0f, 1f, 0.5f), label = "2")
    private val drumPadBd = GesturePad("bd", PadType.DRUM, voiceIndex = null, drumType = 0,
        bounds = NormalizedRect(0f, 0.5f, 0.5f, 1f), label = "BD")

    private val pads = listOf(voicePad0, voicePad1, drumPadBd)

    private fun engine() = PadInteractionEngine(pads)

    private fun finger(
        f: Finger, x: Float, y: Float, z: Float = -0.2f, pressed: Boolean = true,
        handedness: Handedness = Handedness.RIGHT,
    ) = FingerState(f, x, y, z, pressed, handedness = handedness)

    private val noPinch = PinchState(false, 0.5f, 0.5f, 0f)

    @Test
    fun `pressing finger on voice pad emits PadPressed`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f)),
            pinch = noPinch,
            timestampMs = 0L,
        )
        assertTrue(events.any { it is PadEvent.PadPressed && it.pad.id == "v0" && it.finger == Finger.INDEX })
    }

    @Test
    fun `releasing finger from voice pad emits PadReleased`() {
        val engine = engine()
        // Press
        engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f)),
            pinch = noPinch,
            timestampMs = 0L,
        )
        // Release (not pressed)
        val events = engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f, z = 0f, pressed = false)),
            pinch = noPinch,
            timestampMs = 100L,
        )
        assertTrue(events.any { it is PadEvent.PadReleased && it.pad.id == "v0" })
    }

    @Test
    fun `finger movement on pressed pad emits WobbleMove`() {
        val engine = engine()
        engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f)),
            pinch = noPinch,
            timestampMs = 0L,
        )
        val events = engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.25f, 0.22f)),
            pinch = noPinch,
            timestampMs = 16L,
        )
        assertTrue(events.any { it is PadEvent.WobbleMove && it.pad.id == "v0" })
    }

    @Test
    fun `drum pad press emits DrumTrigger`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(finger(Finger.MIDDLE, 0.2f, 0.7f)),
            pinch = noPinch,
            timestampMs = 0L,
        )
        assertTrue(events.any { it is PadEvent.DrumTrigger && it.drumType == 0 })
    }

    @Test
    fun `two fingers press two different pads simultaneously`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(
                finger(Finger.INDEX, 0.2f, 0.2f),
                finger(Finger.MIDDLE, 0.7f, 0.2f),
            ),
            pinch = noPinch,
            timestampMs = 0L,
        )
        val padPresses = events.filterIsInstance<PadEvent.PadPressed>()
        assertEquals(2, padPresses.size)
        assertTrue(padPresses.any { it.pad.id == "v0" })
        assertTrue(padPresses.any { it.pad.id == "v1" })
    }

    @Test
    fun `two hands same finger press different pads`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(
                finger(Finger.INDEX, 0.2f, 0.2f, handedness = Handedness.LEFT),
                finger(Finger.INDEX, 0.7f, 0.2f, handedness = Handedness.RIGHT),
            ),
            pinch = noPinch,
            timestampMs = 0L,
        )
        val padPresses = events.filterIsInstance<PadEvent.PadPressed>()
        assertEquals(2, padPresses.size)
        assertTrue(padPresses.any { it.pad.id == "v0" && it.handedness == Handedness.LEFT })
        assertTrue(padPresses.any { it.pad.id == "v1" && it.handedness == Handedness.RIGHT })
    }

    @Test
    fun `pinch on voice pad emits PinchOnPad`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(finger(Finger.THUMB, 0.2f, 0.2f), finger(Finger.INDEX, 0.22f, 0.22f)),
            pinch = PinchState(true, 0.21f, 0.21f, 0.9f),
            timestampMs = 0L,
        )
        assertTrue(events.any { it is PadEvent.PinchOnPad && it.pad.id == "v0" })
    }

    @Test
    fun `pinch off all pads emits PinchBend`() {
        val engine = engine()
        val events = engine.update(
            fingers = listOf(finger(Finger.THUMB, 0.7f, 0.7f, pressed = false), finger(Finger.INDEX, 0.72f, 0.72f, pressed = false)),
            pinch = PinchState(true, 0.71f, 0.71f, 0.9f),
            timestampMs = 0L,
        )
        assertTrue(events.any { it is PadEvent.PinchBend })
    }

    @Test
    fun `pinch release emits PinchReleased after pinch was active`() {
        val engine = engine()
        // Pinch active
        engine.update(
            fingers = emptyList(),
            pinch = PinchState(true, 0.8f, 0.2f, 0.9f),
            timestampMs = 0L,
        )
        // Pinch released
        val events = engine.update(
            fingers = emptyList(),
            pinch = noPinch,
            timestampMs = 50L,
        )
        assertTrue(events.any { it is PadEvent.PinchReleased })
    }

    @Test
    fun `double tap on voice pad emits ToggleHold`() {
        val engine = engine()
        // First tap: press
        engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f)),
            pinch = noPinch,
            timestampMs = 0L,
        )
        // First tap: release
        engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f, z = 0f, pressed = false)),
            pinch = noPinch,
            timestampMs = 50L,
        )
        // Second tap: press
        engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f)),
            pinch = noPinch,
            timestampMs = 100L,
        )
        // Second tap: release — should emit ToggleHold
        val events = engine.update(
            fingers = listOf(finger(Finger.INDEX, 0.2f, 0.2f, z = 0f, pressed = false)),
            pinch = noPinch,
            timestampMs = 150L,
        )
        assertTrue(events.any { it is PadEvent.ToggleHold && it.voiceIndex == 0 })
    }

    @Test
    fun `no ToggleHold if taps are too far apart`() {
        val engine = engine()
        engine.update(listOf(finger(Finger.INDEX, 0.2f, 0.2f)), noPinch, 0L)
        engine.update(listOf(finger(Finger.INDEX, 0.2f, 0.2f, z = 0f, pressed = false)), noPinch, 50L)
        // Wait too long
        engine.update(listOf(finger(Finger.INDEX, 0.2f, 0.2f)), noPinch, 500L)
        val events = engine.update(listOf(finger(Finger.INDEX, 0.2f, 0.2f, z = 0f, pressed = false)), noPinch, 550L)
        assertTrue(events.none { it is PadEvent.ToggleHold })
    }

    @Test
    fun `finger leaving pad zone emits PadReleased`() {
        val engine = engine()
        // Press on v0
        engine.update(listOf(finger(Finger.INDEX, 0.2f, 0.2f)), noPinch, 0L)
        // Move out of v0 bounds (x > 0.5)
        val events = engine.update(listOf(finger(Finger.INDEX, 0.6f, 0.2f)), noPinch, 16L)
        assertTrue(events.any { it is PadEvent.PadReleased && it.pad.id == "v0" })
    }
}
