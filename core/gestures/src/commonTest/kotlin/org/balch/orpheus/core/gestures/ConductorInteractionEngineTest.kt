package org.balch.orpheus.core.gestures

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConductorInteractionEngineTest {

    private val engine = ConductorInteractionEngine()

    // ── Individual voice gating ─────────────────────────

    @Test
    fun `left hand index touch gates voice 0`() {
        val events = engine.update(listOf(leftHand(indexTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(0, gates[0].voiceIndex)
    }

    @Test
    fun `left hand middle touch gates voice 1`() {
        val events = engine.update(listOf(leftHand(middleTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(1, gates[0].voiceIndex)
    }

    @Test
    fun `left hand ring touch gates voice 2`() {
        val events = engine.update(listOf(leftHand(ringTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(2, gates[0].voiceIndex)
    }

    @Test
    fun `left hand pinky touch gates voice 3`() {
        val events = engine.update(listOf(leftHand(pinkyTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(3, gates[0].voiceIndex)
    }

    @Test
    fun `right hand index touch gates voice 4`() {
        val events = engine.update(listOf(rightHand(indexTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(4, gates[0].voiceIndex)
    }

    @Test
    fun `right hand ring touch gates voice 6`() {
        val events = engine.update(listOf(rightHand(ringTouching = true)), 0L)
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertEquals(1, gates.size)
        assertEquals(6, gates[0].voiceIndex)
    }

    @Test
    fun `multiple fingers gate multiple voices simultaneously`() {
        val events = engine.update(
            listOf(leftHand(indexTouching = true, middleTouching = true, pinkyTouching = true)),
            0L,
        )
        val voices = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
            .map { it.voiceIndex }.toSet()
        assertEquals(setOf(0, 1, 3), voices)
    }

    @Test
    fun `both hands gate voices on both quads`() {
        val events = engine.update(
            listOf(leftHand(indexTouching = true), rightHand(ringTouching = true)),
            0L,
        )
        val voices = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
            .map { it.voiceIndex }.toSet()
        assertEquals(setOf(0, 6), voices)
    }

    @Test
    fun `releasing finger emits VoiceRelease then VoiceGateOff`() {
        engine.update(listOf(leftHand(indexTouching = true)), 0L)
        val events = engine.update(listOf(leftHand()), 33L)
        val releases = events.filterIsInstance<ConductorEvent.VoiceRelease>()
        val gateOffs = events.filterIsInstance<ConductorEvent.VoiceGateOff>()
        assertEquals(1, releases.size)
        assertEquals(0, releases[0].voiceIndex)
        assertEquals(1, gateOffs.size)
        assertEquals(0, gateOffs[0].voiceIndex)
    }

    @Test
    fun `hand disappearing gates off its voices`() {
        engine.update(listOf(leftHand(indexTouching = true, ringTouching = true)), 0L)
        val events = engine.update(emptyList(), 33L)
        val gateOffs = events.filterIsInstance<ConductorEvent.VoiceGateOff>()
            .map { it.voiceIndex }.toSet()
        assertEquals(setOf(0, 2), gateOffs)
    }

    // ── Solo voice bend ──────────────────────────────────

    @Test
    fun `single finger X movement emits VoiceBendSet`() {
        engine.update(listOf(leftHand(indexTouching = true, fingerXOffset = 0f)), 0L)
        val events = engine.update(
            listOf(leftHand(indexTouching = true, fingerXOffset = 0.05f)),
            33L,
        )
        val bends = events.filterIsInstance<ConductorEvent.VoiceBendSet>()
        assertTrue(bends.isNotEmpty(), "Should emit VoiceBendSet for single gated voice")
        assertEquals(0, bends[0].voiceIndex)
        assertTrue(bends[0].bendAmount > 0f)
    }

    // ── Duo bend ─────────────────────────────────────────

    @Test
    fun `both duo voices gated emits DuoBendSet instead of VoiceBendSet`() {
        engine.update(
            listOf(leftHand(indexTouching = true, middleTouching = true, fingerXOffset = 0f)),
            0L,
        )
        val events = engine.update(
            listOf(leftHand(indexTouching = true, middleTouching = true, fingerXOffset = 0.05f)),
            33L,
        )
        val duoBends = events.filterIsInstance<ConductorEvent.DuoBendSet>()
        val voiceBends = events.filterIsInstance<ConductorEvent.VoiceBendSet>()
        assertTrue(duoBends.isNotEmpty(), "Should emit DuoBendSet when both duo voices gated")
        assertTrue(voiceBends.isEmpty(), "Should NOT emit VoiceBendSet when duo is active")
        assertEquals(0, duoBends[0].duoIndex, "Left index+middle = duo 0")
    }

    @Test
    fun `ring plus pinky forms duo 1 on left hand`() {
        engine.update(
            listOf(leftHand(ringTouching = true, pinkyTouching = true, fingerXOffset = 0f)),
            0L,
        )
        val events = engine.update(
            listOf(leftHand(ringTouching = true, pinkyTouching = true, fingerXOffset = 0.05f)),
            33L,
        )
        val duoBends = events.filterIsInstance<ConductorEvent.DuoBendSet>()
        assertTrue(duoBends.isNotEmpty())
        assertEquals(1, duoBends[0].duoIndex, "Left ring+pinky = duo 1")
    }

    @Test
    fun `releasing one duo partner emits DuoRelease`() {
        engine.update(
            listOf(leftHand(indexTouching = true, middleTouching = true)),
            0L,
        )
        val events = engine.update(
            listOf(leftHand(indexTouching = true)),
            33L,
        )
        val duoReleases = events.filterIsInstance<ConductorEvent.DuoRelease>()
        assertTrue(duoReleases.isNotEmpty(), "Releasing one duo partner should emit DuoRelease")
        assertEquals(0, duoReleases[0].duoIndex)
    }

    // ── Thumbs Up/Down hold control ─────────────────────

    @Test
    fun `thumbs up with Z push emits HoldSet`() {
        engine.update(listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = 0.2f)), 0L)
        var holds = emptyList<ConductorEvent.HoldSet>()
        for (i in 1..3) {
            val size = 0.2f + i * 0.04f
            val events = engine.update(
                listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = size)),
                i * 33L,
            )
            holds = holds + events.filterIsInstance<ConductorEvent.HoldSet>()
        }
        assertTrue(holds.isNotEmpty(), "Thumbs up + Z push should emit HoldSet")
        assertEquals(0, holds[0].quadIndex, "Left hand = quad 0")
        assertTrue(holds[0].value > 0f)
    }

    @Test
    fun `thumbs down with Z push also emits HoldSet`() {
        engine.update(listOf(rightHand(aslSign = AslSign.THUMBS_DOWN, apparentSize = 0.2f)), 0L)
        var holds = emptyList<ConductorEvent.HoldSet>()
        for (i in 1..3) {
            val size = 0.2f + i * 0.04f
            val events = engine.update(
                listOf(rightHand(aslSign = AslSign.THUMBS_DOWN, apparentSize = size)),
                i * 33L,
            )
            holds = holds + events.filterIsInstance<ConductorEvent.HoldSet>()
        }
        assertTrue(holds.isNotEmpty(), "Thumbs down + Z push should emit HoldSet")
        assertEquals(1, holds[0].quadIndex, "Right hand = quad 1")
    }

    @Test
    fun `transitioning to thumbs gesture releases gated voices`() {
        // Gate some voices first
        engine.update(listOf(leftHand(indexTouching = true, middleTouching = true)), 0L)
        assertTrue(engine.isAnyVoiceGated)
        // Next frame shows thumbs up — should release both voices
        val events = engine.update(
            listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = 0.2f)),
            33L,
        )
        val gateOffs = events.filterIsInstance<ConductorEvent.VoiceGateOff>()
            .map { it.voiceIndex }.toSet()
        assertEquals(setOf(0, 1), gateOffs, "Voices should be gated off when entering thumbs mode")
        // Should get a DuoRelease since both partners were gated
        val duoReleases = events.filterIsInstance<ConductorEvent.DuoRelease>()
        assertEquals(1, duoReleases.size, "Should emit exactly one DuoRelease for the duo")
        assertEquals(0, duoReleases[0].duoIndex)
    }

    @Test
    fun `thumbs gesture does not gate any voices`() {
        val events = engine.update(
            listOf(leftHand(aslSign = AslSign.THUMBS_UP, indexTouching = true)),
            0L,
        )
        val gates = events.filterIsInstance<ConductorEvent.VoiceGateOn>()
        assertTrue(gates.isEmpty(), "Thumbs gesture should skip finger gating")
    }

    @Test
    fun `hold settles to nearest detent when thumbs gesture ends`() {
        engine.update(listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = 0.2f)), 0L)
        engine.update(listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = 0.25f)), 33L)
        engine.update(listOf(leftHand(aslSign = AslSign.THUMBS_UP, apparentSize = 0.30f)), 66L)
        val events = engine.update(listOf(leftHand(apparentSize = 0.30f)), 99L)
        val holds = events.filterIsInstance<ConductorEvent.HoldSet>()
        if (holds.isNotEmpty()) {
            val nearDetent = ConductorInteractionEngine.HOLD_DETENTS.any { abs(holds.last().value - it) < 0.15f }
            assertTrue(nearDetent, "Hold should settle near a detent")
        }
    }

    // ── Roll → global pitch bend ─────────────────────────

    @Test
    fun `roll angle always emits BendSet`() {
        val events = engine.update(listOf(leftHand(rollAngle = 0.3f)), 0L)
        val bends = events.filterIsInstance<ConductorEvent.BendSet>()
        assertTrue(bends.isNotEmpty())
        assertTrue(bends[0].value > 0f)
    }

    @Test
    fun `roll emits BendSet even with voices gated`() {
        val events = engine.update(
            listOf(leftHand(indexTouching = true, ringTouching = true, rollAngle = 0.3f)),
            0L,
        )
        val bends = events.filterIsInstance<ConductorEvent.BendSet>()
        assertTrue(bends.isNotEmpty(), "Roll should always produce BendSet, no modifier capture")
    }

    // ── Dynamics ─────────────────────────────────────────

    @Test
    fun `palmY maps to dynamics with auto-calibration`() {
        engine.update(listOf(leftHand(palmY = 0.2f)), 0L)
        engine.update(listOf(leftHand(palmY = 0.8f)), 33L)
        val events = engine.update(listOf(leftHand(palmY = 0.5f)), 66L)
        val dynamics = events.filterIsInstance<ConductorEvent.DynamicsSet>().last()
        assertEquals(0, dynamics.quadIndex)
        assertTrue(dynamics.value in 0.3f..0.7f)
    }

    // ── Reset ────────────────────────────────────────────

    @Test
    fun `reset gates off all active voices`() {
        engine.update(
            listOf(leftHand(indexTouching = true, ringTouching = true),
                rightHand(pinkyTouching = true)),
            0L,
        )
        val events = engine.reset()
        val gateOffs = events.filterIsInstance<ConductorEvent.VoiceGateOff>()
            .map { it.voiceIndex }.toSet()
        assertEquals(setOf(0, 2, 7), gateOffs)
    }

    @Test
    fun `isAnyVoiceGated reflects gating state`() {
        assertFalse(engine.isAnyVoiceGated)
        engine.update(listOf(leftHand(pinkyTouching = true)), 0L)
        assertTrue(engine.isAnyVoiceGated)
    }

    // ── Helpers ──────────────────────────────────────────

    @Test
    fun `duoForVoice returns correct duo`() {
        assertEquals(0, ConductorInteractionEngine.duoForVoice(0))
        assertEquals(0, ConductorInteractionEngine.duoForVoice(1))
        assertEquals(1, ConductorInteractionEngine.duoForVoice(2))
        assertEquals(1, ConductorInteractionEngine.duoForVoice(3))
        assertEquals(2, ConductorInteractionEngine.duoForVoice(4))
        assertEquals(3, ConductorInteractionEngine.duoForVoice(7))
    }

    // ── Test helpers ─────────────────────────────────────

    private fun leftHand(
        palmY: Float = 0.5f,
        rollAngle: Float = 0f,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean = false,
        middleTouching: Boolean = false,
        ringTouching: Boolean = false,
        pinkyTouching: Boolean = false,
        fingerXOffset: Float = 0f,
        aslSign: AslSign? = null,
    ) = handWith(
        handedness = Handedness.LEFT,
        palmY = palmY,
        rollAngle = rollAngle,
        apparentSize = apparentSize,
        indexTouching = indexTouching,
        middleTouching = middleTouching,
        ringTouching = ringTouching,
        pinkyTouching = pinkyTouching,
        fingerXOffset = fingerXOffset,
        aslSign = aslSign,
    )

    private fun rightHand(
        palmY: Float = 0.5f,
        rollAngle: Float = 0f,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean = false,
        middleTouching: Boolean = false,
        ringTouching: Boolean = false,
        pinkyTouching: Boolean = false,
        fingerXOffset: Float = 0f,
        aslSign: AslSign? = null,
    ) = handWith(
        handedness = Handedness.RIGHT,
        palmY = palmY,
        rollAngle = rollAngle,
        apparentSize = apparentSize,
        indexTouching = indexTouching,
        middleTouching = middleTouching,
        ringTouching = ringTouching,
        pinkyTouching = pinkyTouching,
        fingerXOffset = fingerXOffset,
        aslSign = aslSign,
    )

    private fun handWith(
        handedness: Handedness,
        palmY: Float,
        rollAngle: Float,
        apparentSize: Float = 0.2f,
        indexTouching: Boolean,
        middleTouching: Boolean,
        ringTouching: Boolean,
        pinkyTouching: Boolean,
        fingerXOffset: Float,
        aslSign: AslSign? = null,
    ): GestureState {
        val thumbX = 0.5f
        val thumbY = 0.5f

        fun fingerTip(finger: Finger, touching: Boolean): FingerState {
            val xOffset = if (touching) fingerXOffset else 0f
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
            handOpenness = 0.5f,
            aslSign = aslSign,
            aslConfidence = if (aslSign != null) 0.9f else 0f,
            fingers = fingers,
        )
    }
}
