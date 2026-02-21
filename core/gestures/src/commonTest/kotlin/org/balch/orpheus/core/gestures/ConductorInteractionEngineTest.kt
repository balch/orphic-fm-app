package org.balch.orpheus.core.gestures

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConductorInteractionEngineTest {

    private val engine = ConductorInteractionEngine()

    // ── String gating ─────────────────────────────────────

    @Test
    fun `left hand index touch gates string 0`() {
        val events = engine.update(listOf(leftHand(indexTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size)
        assertEquals(0, gates[0].stringIndex)
    }

    @Test
    fun `left hand middle touch gates string 1`() {
        val events = engine.update(listOf(leftHand(middleTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size)
        assertEquals(1, gates[0].stringIndex)
    }

    @Test
    fun `right hand index touch gates string 2`() {
        val events = engine.update(listOf(rightHand(indexTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size)
        assertEquals(2, gates[0].stringIndex)
    }

    @Test
    fun `right hand middle touch gates string 3`() {
        val events = engine.update(listOf(rightHand(middleTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size)
        assertEquals(3, gates[0].stringIndex)
    }

    @Test
    fun `both hands gate strings on both quads`() {
        val events = engine.update(
            listOf(leftHand(indexTouching = true), rightHand(indexTouching = true)),
            0L,
        )
        val strings = events.filterIsInstance<ConductorEvent.StringGateOn>()
            .map { it.stringIndex }.toSet()
        assertEquals(setOf(0, 2), strings)
    }

    @Test
    fun `releasing finger emits StringRelease then StringGateOff`() {
        engine.update(listOf(leftHand(indexTouching = true)), 0L)
        val events = engine.update(listOf(leftHand()), 33L)
        val releases = events.filterIsInstance<ConductorEvent.StringRelease>()
        val gateOffs = events.filterIsInstance<ConductorEvent.StringGateOff>()
        assertEquals(1, releases.size)
        assertEquals(0, releases[0].stringIndex)
        assertEquals(1, gateOffs.size)
        assertEquals(0, gateOffs[0].stringIndex)
    }

    @Test
    fun `hand disappearing gates off its strings`() {
        engine.update(listOf(leftHand(indexTouching = true)), 0L)
        val events = engine.update(emptyList(), 33L)
        val gateOffs = events.filterIsInstance<ConductorEvent.StringGateOff>()
        assertEquals(1, gateOffs.size)
        assertEquals(0, gateOffs[0].stringIndex)
    }

    // ── Per-string bend ──────────────────────────────────

    @Test
    fun `finger X movement while gated emits StringBendSet`() {
        engine.update(listOf(leftHand(indexTouching = true, fingerXOffset = 0f)), 0L)
        // Move finger X while still touching
        val events = engine.update(
            listOf(leftHand(indexTouching = true, fingerXOffset = 0.05f)),
            33L,
        )
        val bends = events.filterIsInstance<ConductorEvent.StringBendSet>()
        assertTrue(bends.isNotEmpty(), "Should emit bend while gated")
        assertEquals(0, bends[0].stringIndex)
        assertTrue(bends[0].bendAmount > 0f, "Positive X offset should produce positive bend")
    }

    @Test
    fun `bend is normalized relative to gate-on thumb position`() {
        // Gate on with finger at thumb X
        engine.update(listOf(leftHand(indexTouching = true, fingerXOffset = 0f)), 0L)
        // Move finger within touch range — offset 0.05 from gate-on position
        // Total distance from thumb = 0.02 + 0.05 = 0.07, still within 0.08 threshold
        val events = engine.update(
            listOf(leftHand(indexTouching = true, fingerXOffset = 0.05f)),
            33L,
        )
        val bend = events.filterIsInstance<ConductorEvent.StringBendSet>().last()
        // bendDelta=0.07 / (apparentSize*BEND_X_RATIO) = 0.07/0.136 ≈ 0.51
        assertTrue(bend.bendAmount in 0.3f..0.7f,
            "Expected bend ~0.5, got ${bend.bendAmount}")
    }

    // ── Roll angle routing ───────────────────────────────

    @Test
    fun `roll angle with no modifier emits BendSet`() {
        val events = engine.update(listOf(leftHand(rollAngle = 0.3f)), 0L)
        val bends = events.filterIsInstance<ConductorEvent.BendSet>()
        assertTrue(bends.isNotEmpty(), "Roll without modifier should emit BendSet")
        assertTrue(bends[0].value > 0f, "Positive roll should produce positive bend")
    }

    @Test
    fun `pinky with Z push forward emits HoldSet increasing`() {
        // Frame 1: establish baseline apparentSize
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.2f)), 0L)
        // Frames 2-4: sustained push forward (must exceed breakout threshold after smoothing)
        var holds = emptyList<ConductorEvent.HoldSet>()
        for (i in 1..3) {
            val size = 0.2f + i * 0.04f // deliberate push
            val events = engine.update(
                listOf(leftHand(pinkyTouching = true, apparentSize = size)),
                i * 33L,
            )
            holds = holds + events.filterIsInstance<ConductorEvent.HoldSet>()
        }
        assertTrue(holds.isNotEmpty(), "Pinky + Z push should emit HoldSet")
        assertEquals(0, holds[0].quadIndex, "Left hand → quad 0")
        assertTrue(holds[0].value > 0f, "Forward push should increase hold")
    }

    @Test
    fun `pinky with Z pull back decreases hold`() {
        // Build up hold first with large deliberate pushes
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.2f)), 0L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.25f)), 33L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.30f)), 66L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.35f)), 99L)
        // Now pull back sharply
        val events = engine.update(
            listOf(leftHand(pinkyTouching = true, apparentSize = 0.25f)),
            132L,
        )
        val holds = events.filterIsInstance<ConductorEvent.HoldSet>()
        assertTrue(holds.isNotEmpty(), "Pull-back should emit HoldSet")
    }

    @Test
    fun `pinky hold settles near detent positions`() {
        // Push forward with deliberate sustained movement
        var lastHold = 0f
        for (i in 0..15) {
            val size = 0.2f + i * 0.01f // deliberate push
            val events = engine.update(
                listOf(leftHand(pinkyTouching = true, apparentSize = size)),
                i * 33L,
            )
            val hold = events.filterIsInstance<ConductorEvent.HoldSet>().lastOrNull()
            if (hold != null) lastHold = hold.value
        }
        // Then hold steady to let it settle
        for (i in 16..25) {
            val events = engine.update(
                listOf(leftHand(pinkyTouching = true, apparentSize = 0.35f)),
                i * 33L,
            )
            val hold = events.filterIsInstance<ConductorEvent.HoldSet>().lastOrNull()
            if (hold != null) lastHold = hold.value
        }
        // Should have settled near one of the detents
        val nearDetent = ConductorInteractionEngine.HOLD_DETENTS.any { abs(lastHold - it) < 0.15f }
        assertTrue(nearDetent, "Hold ($lastHold) should be near a detent position")
    }

    @Test
    fun `hold stops emitting once settled at detent`() {
        // Push forward with deliberate movement
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.2f)), 0L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.25f)), 33L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.30f)), 66L)

        // Now hold steady — same apparentSize for many frames
        var emittedCount = 0
        for (i in 3..30) {
            val events = engine.update(
                listOf(leftHand(pinkyTouching = true, apparentSize = 0.30f)),
                i * 33L,
            )
            emittedCount += events.filterIsInstance<ConductorEvent.HoldSet>().size
        }
        // Velocity smoothing takes a few frames to decay, then snaps and stops
        assertTrue(emittedCount < 8,
            "Hold should stop emitting once settled, but emitted $emittedCount times in 28 frames")
    }

    @Test
    fun `pinky does not capture roll — bend still emits`() {
        // Pinky touching but roll should still go to bend (not hold)
        val events = engine.update(
            listOf(leftHand(pinkyTouching = true, rollAngle = 0.3f, apparentSize = 0.2f)),
            0L,
        )
        val bends = events.filterIsInstance<ConductorEvent.BendSet>()
        assertTrue(bends.isNotEmpty(), "Roll should still emit BendSet even with pinky active")
    }

    @Test
    fun `roll angle with ring touching emits ModSourceLevelSet`() {
        val events = engine.update(
            listOf(leftHand(ringTouching = true, rollAngle = 0.3f)),
            0L,
        )
        val levels = events.filterIsInstance<ConductorEvent.ModSourceLevelSet>()
        assertTrue(levels.isNotEmpty(), "Ring + roll should emit ModSourceLevelSet")
        assertEquals(0, levels[0].quadIndex)
    }

    @Test
    fun `ring takes priority over bend for roll routing`() {
        val events = engine.update(
            listOf(leftHand(ringTouching = true, rollAngle = 0.3f)),
            0L,
        )
        val levels = events.filterIsInstance<ConductorEvent.ModSourceLevelSet>()
        val bends = events.filterIsInstance<ConductorEvent.BendSet>()
        assertTrue(levels.isNotEmpty(), "Ring should capture roll")
        assertTrue(bends.isEmpty(), "Bend should be suppressed when ring active")
    }

    @Test
    fun `roll values are smoothed over multiple frames`() {
        var lastBend = 0f
        for (i in 0..10) {
            val events = engine.update(listOf(leftHand(rollAngle = 0.4f)), i * 33L)
            val bend = events.filterIsInstance<ConductorEvent.BendSet>().lastOrNull()
            if (bend != null) {
                assertTrue(bend.value >= lastBend, "Bend should monotonically increase")
                lastBend = bend.value
            }
        }
        assertTrue(lastBend > 0.3f, "After 10 frames, bend should approach target")
    }

    // ── Ring double-tap ──────────────────────────────────

    @Test
    fun `ring double-tap emits ModSourceCycle`() {
        // First tap
        engine.update(listOf(leftHand(ringTouching = true)), 0L)
        // Release ring
        engine.update(listOf(leftHand()), 100L)
        // Second tap within 400ms
        val events = engine.update(listOf(leftHand(ringTouching = true)), 300L)
        val cycles = events.filterIsInstance<ConductorEvent.ModSourceCycle>()
        assertEquals(1, cycles.size, "Double-tap should emit ModSourceCycle")
        assertEquals(0, cycles[0].quadIndex)
    }

    @Test
    fun `ring single tap does not emit ModSourceCycle`() {
        val events = engine.update(listOf(leftHand(ringTouching = true)), 0L)
        val cycles = events.filterIsInstance<ConductorEvent.ModSourceCycle>()
        assertTrue(cycles.isEmpty(), "Single tap should not cycle mod source")
    }

    @Test
    fun `ring taps too far apart do not cycle`() {
        // First tap
        engine.update(listOf(leftHand(ringTouching = true)), 0L)
        // Release
        engine.update(listOf(leftHand()), 100L)
        // Second tap after 400ms window
        val events = engine.update(listOf(leftHand(ringTouching = true)), 500L)
        val cycles = events.filterIsInstance<ConductorEvent.ModSourceCycle>()
        assertTrue(cycles.isEmpty(), "Taps >400ms apart should not cycle")
    }

    // ── Pinky double-tap (hold reset) ───────────────────

    @Test
    fun `pinky double-tap resets hold to zero`() {
        // Build up some hold first (first pinky touch-on at t=0 sets lastPinkyTapMs=0)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.2f)), 0L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.25f)), 33L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.30f)), 66L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.35f)), 99L)
        // Release pinky
        engine.update(listOf(leftHand()), 200L)
        // First tap of the double-tap sequence
        engine.update(listOf(leftHand(pinkyTouching = true)), 1000L)
        // Release
        engine.update(listOf(leftHand()), 1100L)
        // Second tap within 400ms of first tap → double-tap
        val events = engine.update(listOf(leftHand(pinkyTouching = true)), 1300L)
        val holds = events.filterIsInstance<ConductorEvent.HoldSet>()
        assertTrue(holds.isNotEmpty(), "Pinky double-tap should emit HoldSet")
        assertEquals(0f, holds.last().value, "Pinky double-tap should reset hold to 0")
        assertEquals(0, holds.last().quadIndex, "Left hand → quad 0")
    }

    @Test
    fun `pinky taps too far apart do not reset hold`() {
        // Build up hold
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.2f)), 0L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.30f)), 33L)
        engine.update(listOf(leftHand(pinkyTouching = true, apparentSize = 0.35f)), 66L)
        // Release pinky
        engine.update(listOf(leftHand()), 100L)
        // Second tap after 400ms window — should NOT reset
        val events = engine.update(listOf(leftHand(pinkyTouching = true)), 600L)
        val holds = events.filterIsInstance<ConductorEvent.HoldSet>()
        // Should not have a 0.0 hold reset
        assertTrue(holds.none { it.value == 0f },
            "Pinky taps >400ms apart should not reset hold")
    }

    // ── Dynamics & Timbre ────────────────────────────────

    @Test
    fun `palmY maps to dynamics with auto-calibration`() {
        engine.update(listOf(leftHand(palmY = 0.2f)), 0L)
        engine.update(listOf(leftHand(palmY = 0.8f)), 33L)
        val events = engine.update(listOf(leftHand(palmY = 0.5f)), 66L)
        val dynamics = events.filterIsInstance<ConductorEvent.DynamicsSet>().last()
        assertEquals(0, dynamics.quadIndex)
        assertTrue(dynamics.value in 0.3f..0.7f,
            "Mid palmY should produce ~0.5 dynamics, got ${dynamics.value}")
    }

    @Test
    fun `hand openness maps to timbre`() {
        val events = engine.update(listOf(leftHand(handOpenness = 0.9f)), 0L)
        val timbre = events.filterIsInstance<ConductorEvent.TimbreSet>().last()
        assertTrue(timbre.value > 0.8f)
    }

    // ── Distance invariance ──────────────────────────────

    @Test
    fun `touch gating works at close distance (large apparentSize)`() {
        val events = engine.update(
            listOf(leftHand(indexTouching = true, apparentSize = 0.35f)),
            0L,
        )
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size, "Should gate at close distance (apparentSize=0.35)")
    }

    @Test
    fun `touch gating works at far distance (small apparentSize)`() {
        val engine2 = ConductorInteractionEngine()
        val events = engine2.update(
            listOf(leftHand(indexTouching = true, apparentSize = 0.12f)),
            0L,
        )
        val gates = events.filterIsInstance<ConductorEvent.StringGateOn>()
        assertEquals(1, gates.size, "Should gate at far distance (apparentSize=0.12)")
    }

    // ── Reset ────────────────────────────────────────────

    @Test
    fun `reset gates off all active strings`() {
        engine.update(
            listOf(leftHand(indexTouching = true, middleTouching = true),
                rightHand(indexTouching = true)),
            0L,
        )
        val events = engine.reset()
        val gateOffs = events.filterIsInstance<ConductorEvent.StringGateOff>()
            .map { it.stringIndex }.toSet()
        assertEquals(setOf(0, 1, 2), gateOffs)
        val releases = events.filterIsInstance<ConductorEvent.StringRelease>()
        assertEquals(3, releases.size)
    }

    @Test
    fun `no hands produces no gate events`() {
        val events = engine.update(emptyList(), 0L)
        assertTrue(events.filterIsInstance<ConductorEvent.StringGateOn>().isEmpty())
    }

    // ── Voice mapping helper ─────────────────────────────

    @Test
    fun `voicesForString returns correct duo`() {
        assertEquals(Pair(0, 1), ConductorInteractionEngine.voicesForString(0))
        assertEquals(Pair(2, 3), ConductorInteractionEngine.voicesForString(1))
        assertEquals(Pair(4, 5), ConductorInteractionEngine.voicesForString(2))
        assertEquals(Pair(6, 7), ConductorInteractionEngine.voicesForString(3))
    }

    // ── Test helpers ─────────────────────────────────────

    private fun leftHand(
        palmY: Float = 0.5f,
        handOpenness: Float = 0.5f,
        rollAngle: Float = 0f,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean = false,
        middleTouching: Boolean = false,
        ringTouching: Boolean = false,
        pinkyTouching: Boolean = false,
        fingerXOffset: Float = 0f,
    ) = handWith(
        handedness = Handedness.LEFT,
        palmY = palmY,
        handOpenness = handOpenness,
        rollAngle = rollAngle,
        apparentSize = apparentSize,
        indexTouching = indexTouching,
        middleTouching = middleTouching,
        ringTouching = ringTouching,
        pinkyTouching = pinkyTouching,
        fingerXOffset = fingerXOffset,
    )

    private fun rightHand(
        palmY: Float = 0.5f,
        handOpenness: Float = 0.5f,
        rollAngle: Float = 0f,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean = false,
        middleTouching: Boolean = false,
        ringTouching: Boolean = false,
        pinkyTouching: Boolean = false,
        fingerXOffset: Float = 0f,
    ) = handWith(
        handedness = Handedness.RIGHT,
        palmY = palmY,
        handOpenness = handOpenness,
        rollAngle = rollAngle,
        apparentSize = apparentSize,
        indexTouching = indexTouching,
        middleTouching = middleTouching,
        ringTouching = ringTouching,
        pinkyTouching = pinkyTouching,
        fingerXOffset = fingerXOffset,
    )

    /**
     * Build GestureState with finger positions relative to thumb.
     * Touching = 0.02 from thumb, not touching = 0.25 from thumb.
     * fingerXOffset shifts string-gating fingers (index/middle) X for bend testing.
     */
    private fun handWith(
        handedness: Handedness,
        palmY: Float,
        handOpenness: Float,
        rollAngle: Float,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean,
        middleTouching: Boolean,
        ringTouching: Boolean,
        pinkyTouching: Boolean,
        fingerXOffset: Float,
    ): GestureState {
        val thumbX = 0.5f
        val thumbY = 0.5f

        fun fingerTip(finger: Finger, touching: Boolean): FingerState {
            val xOffset = if (touching && (finger == Finger.INDEX || finger == Finger.MIDDLE))
                fingerXOffset else 0f
            // When touching with X offset, keep total distance within threshold
            // by positioning along X axis only (Y stays at thumbY)
            val tipX = if (touching) thumbX + 0.02f + xOffset else thumbX + 0.25f
            val tipY = thumbY
            return FingerState(
                finger = finger,
                tipX = tipX,
                tipY = tipY,
                tipZ = 0f,
                isPressed = touching,
                isExtended = !touching,
                handedness = handedness,
            )
        }

        val fingers = listOf(
            FingerState(Finger.THUMB, thumbX, thumbY, 0f, isPressed = false, handedness = handedness),
            fingerTip(Finger.INDEX, indexTouching),
            fingerTip(Finger.MIDDLE, middleTouching),
            fingerTip(Finger.RING, ringTouching),
            fingerTip(Finger.PINKY, pinkyTouching),
        )

        return GestureState(
            isPinching = false,
            pinchStrength = 0f,
            palmX = 0.5f,
            palmY = palmY,
            apparentSize = apparentSize,
            rollAngle = rollAngle,
            ringFingerDirection = 0f,
            handedness = handedness,
            handOpenness = handOpenness,
            aslSign = null,
            aslConfidence = 0f,
            fingers = fingers,
        )
    }
}
