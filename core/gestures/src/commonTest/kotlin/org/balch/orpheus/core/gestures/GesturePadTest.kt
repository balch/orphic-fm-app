package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GesturePadTest {

    private val pads = listOf(
        GesturePad(
            id = "voice_0",
            type = PadType.VOICE,
            voiceIndex = 0,
            drumType = null,
            bounds = NormalizedRect(0.0f, 0.0f, 0.25f, 0.5f),
            label = "1",
        ),
        GesturePad(
            id = "voice_1",
            type = PadType.VOICE,
            voiceIndex = 1,
            drumType = null,
            bounds = NormalizedRect(0.25f, 0.0f, 0.5f, 0.5f),
            label = "2",
        ),
        GesturePad(
            id = "drum_bd",
            type = PadType.DRUM,
            voiceIndex = null,
            drumType = 0,
            bounds = NormalizedRect(0.0f, 0.5f, 0.33f, 1.0f),
            label = "BD",
        ),
    )

    @Test
    fun `hitTest returns pad when point is inside bounds`() {
        val hit = pads.hitTest(0.1f, 0.2f)
        assertEquals("voice_0", hit?.id)
    }

    @Test
    fun `hitTest returns null when point is outside all pads`() {
        val hit = pads.hitTest(0.9f, 0.9f)
        assertNull(hit)
    }

    @Test
    fun `hitTest returns correct pad for drum region`() {
        val hit = pads.hitTest(0.15f, 0.7f)
        assertEquals("drum_bd", hit?.id)
    }

    @Test
    fun `NormalizedRect contains point on left boundary`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.9f)
        assertTrue(rect.contains(0.2f, 0.5f))
    }

    @Test
    fun `NormalizedRect does not contain point on right boundary`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.9f)
        assertEquals(false, rect.contains(0.8f, 0.5f))
    }
}
