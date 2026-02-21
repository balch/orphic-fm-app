package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AslInteractionEngineTest {
    // Use debounceFrames=1 for deterministic tests
    private val engine = AslInteractionEngine(confidenceThreshold = 0.5f, debounceFrames = 1)

    @Test
    fun `idle state when no ASL sign detected`() {
        val events = engine.update(listOf(gestureState(aslSign = null, isPinching = false)))
        assertEquals(InteractionPhase.IDLE, engine.phase)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `transitions to SELECTED when ASL number shown`() {
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false)
        ))
        assertEquals(InteractionPhase.SELECTED, engine.phase)
        assertTrue(events.any { it is AslEvent.TargetSelected })
    }

    @Test
    fun `pinch start gates voice on`() {
        // First frame: show number 3
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        // Second frame: pinch with other hand
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ))
        assertTrue(events.any { it is AslEvent.VoiceGateOn && it.voiceIndex == 2 },
            "Pinch start should gate voice on")
    }

    @Test
    fun `single pinch release gates voice off`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ))
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = false),
        ), timestampMs = 1000L)
        assertTrue(events.any { it is AslEvent.VoiceGateOff && it.voiceIndex == 2 },
            "Single pinch release should gate voice off")
    }

    @Test
    fun `double pinch toggles hold`() {
        // Select voice 3
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        // First pinch
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ), timestampMs = 100L)
        // First release — gates off, records timestamp
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
        ), timestampMs = 200L)
        // Second pinch — gates on again
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ), timestampMs = 300L)
        // Second release within 400ms window — toggles hold (no gate off)
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
        ), timestampMs = 400L)
        assertTrue(events.any { it is AslEvent.HoldToggle && it.voiceIndex == 2 },
            "Double-pinch should toggle hold")
        assertTrue(events.none { it is AslEvent.VoiceGateOff },
            "Double-pinch should not gate off (hold keeps voice alive)")
    }

    @Test
    fun `thumbs up does not send hold toggle`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        val events = engine.update(listOf(gestureState(aslSign = AslSign.THUMBS_UP)))
        assertTrue(events.none { it is AslEvent.HoldToggle },
            "Thumbs up should no longer send HoldToggle (used for swipe)")
    }

    @Test
    fun `thumbs down sends hold off when target selected`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        val events = engine.update(listOf(gestureState(aslSign = AslSign.THUMBS_DOWN)))
        assertTrue(events.any { it is AslEvent.HoldOff && it.voiceIndex == 2 },
            "Thumbs down should send HoldOff for selected voice")
    }

    @Test
    fun `pinch depth change produces env speed adjust`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        // Start pinch at baseline size
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f, apparentSize = 0.2f),
        ))
        // Hand moves closer to camera — apparent size grows
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f, apparentSize = 0.25f),
        ))
        assertTrue(events.any { it is AslEvent.EnvSpeedAdjust },
            "Apparent size change during pinch should produce EnvSpeedAdjust")
    }

    @Test
    fun `parameter sign plus pinch drag produces adjustment`() {
        // Select voice 3, then show M (morph) and pinch-drag
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_M, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ))
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_M, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.3f), // dragged up
        ))
        assertTrue(events.any { it is AslEvent.ParameterAdjust })
    }

    @Test
    fun `duo prefix D then number selects duo`() {
        engine.update(listOf(gestureState(aslSign = AslSign.LETTER_D)))
        val events = engine.update(listOf(gestureState(aslSign = AslSign.NUM_2)))
        assertTrue(events.any { it is AslEvent.DuoSelected && it.duoIndex == 1 })
    }

    @Test
    fun `quad prefix Q then number selects quad`() {
        engine.update(listOf(gestureState(aslSign = AslSign.LETTER_Q)))
        val events = engine.update(listOf(gestureState(aslSign = AslSign.NUM_1)))
        assertTrue(events.any { it is AslEvent.QuadSelected && it.quadIndex == 0 })
    }

    @Test
    fun `fist A deselects all`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        val events = engine.update(listOf(gestureState(aslSign = AslSign.LETTER_A)))
        assertTrue(events.any { it is AslEvent.TargetDeselected })
        assertEquals(InteractionPhase.SELECTED, engine.phase) // A itself is recognized
    }

    @Test
    fun `ILY sign emits ToggleConductorMode`() {
        val events = engine.update(listOf(gestureState(aslSign = AslSign.ILY)))
        assertTrue(events.any { it is AslEvent.ToggleConductorMode },
            "ILY sign should emit ToggleConductorMode")
    }

    @Test
    fun `reset clears all state`() {
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        engine.reset()
        assertEquals(InteractionPhase.IDLE, engine.phase)
    }

    @Test
    fun `tracking loss during pinch gates off on flush`() {
        // Select voice 3, start pinching
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3, isPinching = false)))
        engine.update(listOf(
            gestureState(aslSign = AslSign.NUM_3, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ), timestampMs = 100L)
        // Hands disappear — update with empty list flushes gate-off
        val events = engine.update(emptyList(), timestampMs = 5000L)
        assertTrue(events.any { it is AslEvent.VoiceGateOff && it.voiceIndex == 2 },
            "Tracking loss should flush VoiceGateOff")
    }

    @Test
    fun `R sign on second hand arms remote adjust — pinch does not gate`() {
        // Select voice 3
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        // Show LETTER_B param
        engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
        // Show LETTER_B (signer) + LETTER_R (second hand) — arms remote adjust
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B),
            gestureState(aslSign = AslSign.LETTER_R),
        ))
        assertTrue(engine.remoteAdjustArmed, "R sign should arm remote adjust")
        // Pinch on second hand while signer shows LETTER_B
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ))
        assertTrue(events.none { it is AslEvent.VoiceGateOn },
            "R-armed pinch should NOT emit VoiceGateOn")
    }

    @Test
    fun `R-armed pinch still emits ParameterAdjust`() {
        // Select voice 3
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        // Show LETTER_B param
        engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
        // Arm remote adjust with R on second hand
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B),
            gestureState(aslSign = AslSign.LETTER_R),
        ))
        // Start pinch
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ))
        // Drag pinch up (palmY 0.5 -> 0.3)
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.3f),
        ))
        assertTrue(events.any { it is AslEvent.ParameterAdjust },
            "R-armed pinch drag should still emit ParameterAdjust")
    }

    @Test
    fun `R-armed pinch release does not gate off or toggle hold`() {
        // Select voice 3
        engine.update(listOf(gestureState(aslSign = AslSign.NUM_3)))
        // Show LETTER_B param
        engine.update(listOf(gestureState(aslSign = AslSign.LETTER_B)))
        // Arm remote adjust with R on second hand
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B),
            gestureState(aslSign = AslSign.LETTER_R),
        ))
        // Start pinch
        engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B, isPinching = false),
            gestureState(aslSign = null, isPinching = true, palmY = 0.5f),
        ), timestampMs = 100L)
        // Release pinch
        val events = engine.update(listOf(
            gestureState(aslSign = AslSign.LETTER_B, isPinching = false),
        ), timestampMs = 200L)
        assertTrue(events.none { it is AslEvent.VoiceGateOff },
            "R-armed pinch release should NOT emit VoiceGateOff")
        assertTrue(events.none { it is AslEvent.HoldToggle },
            "R-armed pinch release should NOT emit HoldToggle")
        assertFalse(engine.remoteAdjustArmed,
            "remoteAdjustArmed should be consumed on release")
    }

    @Test
    fun `system sign V selects target directly`() {
        val events = engine.update(listOf(gestureState(aslSign = AslSign.LETTER_V)))
        assertTrue(events.any { it is AslEvent.TargetSelected && it.sign == AslSign.LETTER_V })
    }

    private fun gestureState(
        aslSign: AslSign? = null,
        isPinching: Boolean = false,
        palmY: Float = 0.5f,
        palmX: Float = 0.5f,
        apparentSize: Float = 0.2f,
    ) = GestureState(
        isPinching = isPinching,
        pinchStrength = if (isPinching) 0.8f else 0f,
        palmX = palmX, palmY = palmY, apparentSize = apparentSize,
        rollAngle = 0f, ringFingerDirection = 0f,
        handedness = Handedness.RIGHT,
        aslSign = aslSign, aslConfidence = if (aslSign != null) 0.9f else 0f,
    )
}
