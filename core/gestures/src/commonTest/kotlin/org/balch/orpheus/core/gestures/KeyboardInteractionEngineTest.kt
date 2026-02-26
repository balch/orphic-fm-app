package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardInteractionEngineTest {

    private val engine = KeyboardInteractionEngine()

    // Zone constants for reference:
    // ZONE_Y_ENTER = 0.40, ZONE_Y_EXIT = 0.36, ZONE_Y_MAX = 0.55
    // "In zone" Y ~0.50, "above zone" Y ~0.20, hysteresis band 0.36–0.40

    // ── Note triggering ─────────────────────────────────

    @Test
    fun `finger entering keyboard zone triggers NoteOn`() {
        // Frame 1: finger above zone
        engine.update(listOf(hand(indexY = 0.20f, indexX = 0.50f)), 0L)
        // Frame 2: finger drops into zone
        val events = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.50f)), 33L)
        val noteOns = events.filterIsInstance<KeyboardEvent.NoteOn>()
        assertEquals(1, noteOns.size, "Should emit exactly one NoteOn")
        assertTrue(noteOns[0].keyIndex in 0..11, "Key index should be valid")
    }

    @Test
    fun `finger leaving zone triggers NoteOff`() {
        // Enter zone
        engine.update(listOf(hand(indexY = 0.50f, indexX = 0.50f)), 0L)
        // Leave zone (below exit threshold 0.36)
        val events = engine.update(listOf(hand(indexY = 0.30f, indexX = 0.50f)), 33L)
        val noteOffs = events.filterIsInstance<KeyboardEvent.NoteOff>()
        assertEquals(1, noteOffs.size, "Should emit NoteOff when leaving zone")
    }

    @Test
    fun `finger sliding between keys triggers NoteOff then NoteOn`() {
        // Enter zone on left-center (within perspective bounds at Y=0.50)
        engine.update(listOf(hand(indexY = 0.50f, indexX = 0.20f)), 0L)
        // Slide to right-center (still within perspective bounds)
        val events = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.75f)), 33L)
        val noteOffs = events.filterIsInstance<KeyboardEvent.NoteOff>()
        val noteOns = events.filterIsInstance<KeyboardEvent.NoteOn>()
        assertEquals(1, noteOffs.size, "Should emit NoteOff for old key")
        assertEquals(1, noteOns.size, "Should emit NoteOn for new key")
        assertTrue(noteOffs[0].keyIndex != noteOns[0].keyIndex, "Old and new key should differ")
    }

    // ── Hysteresis ──────────────────────────────────────

    @Test
    fun `hysteresis prevents flutter at zone boundary`() {
        // Enter zone at Y=0.42 (above ZONE_Y_ENTER=0.40)
        engine.update(listOf(hand(indexY = 0.42f, indexX = 0.50f)), 0L)
        // Move to Y=0.38 — between exit (0.36) and enter (0.40), should stay in zone
        val events = engine.update(listOf(hand(indexY = 0.38f, indexX = 0.50f)), 33L)
        val noteOffs = events.filterIsInstance<KeyboardEvent.NoteOff>()
        assertTrue(noteOffs.isEmpty(), "Should not emit NoteOff in hysteresis band")
    }

    @Test
    fun `finger exits zone only below exit threshold`() {
        // Enter zone
        engine.update(listOf(hand(indexY = 0.50f, indexX = 0.50f)), 0L)
        // Move to Y=0.38 — still above exit threshold 0.36
        val events1 = engine.update(listOf(hand(indexY = 0.38f, indexX = 0.50f)), 33L)
        assertTrue(events1.filterIsInstance<KeyboardEvent.NoteOff>().isEmpty(), "Should not exit at 0.38")
        // Move to Y=0.34 — below exit threshold
        val events2 = engine.update(listOf(hand(indexY = 0.34f, indexX = 0.50f)), 66L)
        assertEquals(1, events2.filterIsInstance<KeyboardEvent.NoteOff>().size, "Should exit at 0.34")
    }

    // ── Multiple fingers ────────────────────────────────

    @Test
    fun `multiple fingers produce independent notes`() {
        val events = engine.update(
            listOf(
                hand(
                    indexY = 0.50f, indexX = 0.15f,
                    middleY = 0.50f, middleX = 0.50f,
                ),
            ),
            0L,
        )
        val noteOns = events.filterIsInstance<KeyboardEvent.NoteOn>()
        assertEquals(2, noteOns.size, "Two fingers should produce two NoteOns")
        assertTrue(
            noteOns[0].keyIndex != noteOns[1].keyIndex,
            "Different fingers at different X should hit different keys",
        )
    }

    // ── Hand disappearing ───────────────────────────────

    @Test
    fun `hand disappearing gates off all active notes`() {
        // Two fingers in zone
        engine.update(
            listOf(
                hand(
                    indexY = 0.50f, indexX = 0.15f,
                    middleY = 0.50f, middleX = 0.50f,
                ),
            ),
            0L,
        )
        // Hand disappears
        val events = engine.update(emptyList(), 33L)
        val noteOffs = events.filterIsInstance<KeyboardEvent.NoteOff>()
        assertEquals(2, noteOffs.size, "All active notes should gate off when hand disappears")
    }

    // ── Reset ───────────────────────────────────────────

    @Test
    fun `reset gates off all notes`() {
        engine.update(
            listOf(
                hand(indexY = 0.50f, indexX = 0.15f),
                hand(
                    handedness = Handedness.RIGHT,
                    indexY = 0.50f, indexX = 0.85f,
                ),
            ),
            0L,
        )
        val events = engine.reset()
        val noteOffs = events.filterIsInstance<KeyboardEvent.NoteOff>()
        assertEquals(2, noteOffs.size, "Reset should gate off all active notes")
    }

    // ── Dead zones ──────────────────────────────────────

    @Test
    fun `finger outside X range produces no events`() {
        // X below KEY_X_MIN
        val events1 = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.03f)), 0L)
        assertTrue(
            events1.filterIsInstance<KeyboardEvent.NoteOn>().isEmpty(),
            "X below KEY_X_MIN should produce no NoteOn",
        )
        // X above KEY_X_MAX
        val events2 = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.97f)), 33L)
        assertTrue(
            events2.filterIsInstance<KeyboardEvent.NoteOn>().isEmpty(),
            "X above KEY_X_MAX should produce no NoteOn",
        )
    }

    // ── xyToKeyIndex mapping (perspective-aware) ────────

    @Test
    fun `xyToKeyIndex maps correctly for edges at bottom of zone`() {
        val yBottom = KeyboardInteractionEngine.ZONE_Y_MAX
        assertEquals(0, KeyboardInteractionEngine.xyToKeyIndex(0.08f, yBottom), "x=0.08 at bottom should be key 0")
        assertEquals(11, KeyboardInteractionEngine.xyToKeyIndex(0.92f, yBottom), "x=0.92 at bottom should be key 11")
    }

    @Test
    fun `xyToKeyIndex maps center to key 5 or 6`() {
        val yBottom = KeyboardInteractionEngine.ZONE_Y_MAX
        val key = KeyboardInteractionEngine.xyToKeyIndex(0.50f, yBottom)
        assertTrue(key in 5..6, "x=0.50 should map near the center keys")
    }

    @Test
    fun `xyToKeyIndex clamps at boundaries`() {
        val yBottom = KeyboardInteractionEngine.ZONE_Y_MAX
        assertEquals(0, KeyboardInteractionEngine.xyToKeyIndex(0.01f, yBottom), "Below min should clamp to 0")
        assertEquals(11, KeyboardInteractionEngine.xyToKeyIndex(0.99f, yBottom), "Above max should clamp to 11")
    }

    // ── Velocity ────────────────────────────────────────

    @Test
    fun `NoteOn velocity is positive`() {
        engine.update(listOf(hand(indexY = 0.20f, indexX = 0.50f)), 0L)
        val events = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.50f)), 33L)
        val noteOns = events.filterIsInstance<KeyboardEvent.NoteOn>()
        assertEquals(1, noteOns.size)
        assertTrue(noteOns[0].velocity > 0f, "Velocity should be positive")
        assertTrue(noteOns[0].velocity <= 1f, "Velocity should not exceed 1.0")
    }

    // ── Finger starts in zone (first frame) ─────────────

    @Test
    fun `finger appearing already in zone triggers NoteOn`() {
        // First ever frame — finger is already in the keyboard zone
        val events = engine.update(listOf(hand(indexY = 0.50f, indexX = 0.50f)), 0L)
        val noteOns = events.filterIsInstance<KeyboardEvent.NoteOn>()
        assertEquals(1, noteOns.size, "Finger starting in zone should trigger NoteOn")
    }

    // ── Test helpers ────────────────────────────────────

    private fun hand(
        handedness: Handedness = Handedness.LEFT,
        indexX: Float = 0.50f,
        indexY: Float = 0.20f,
        middleX: Float = 0.50f,
        middleY: Float = 0.20f,
        ringX: Float = 0.50f,
        ringY: Float = 0.20f,
        pinkyX: Float = 0.50f,
        pinkyY: Float = 0.20f,
    ): GestureState {
        val fingers = listOf(
            FingerState(Finger.THUMB, 0.5f, 0.2f, 0f, isPressed = false, handedness = handedness),
            FingerState(Finger.INDEX, indexX, indexY, 0f, isPressed = false, handedness = handedness),
            FingerState(Finger.MIDDLE, middleX, middleY, 0f, isPressed = false, handedness = handedness),
            FingerState(Finger.RING, ringX, ringY, 0f, isPressed = false, handedness = handedness),
            FingerState(Finger.PINKY, pinkyX, pinkyY, 0f, isPressed = false, handedness = handedness),
        )
        return GestureState(
            isPinching = false,
            pinchStrength = 0f,
            palmX = 0.5f,
            palmY = 0.5f,
            rollAngle = 0f,
            ringFingerDirection = 0f,
            handedness = handedness,
            fingers = fingers,
        )
    }
}
