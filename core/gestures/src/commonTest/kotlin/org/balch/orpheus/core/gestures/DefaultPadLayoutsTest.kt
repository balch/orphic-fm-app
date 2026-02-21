package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPadLayoutsTest {

    @Test
    fun `default layout has 8 voice pads and 3 drum pads`() {
        val layout = DefaultPadLayouts.grid4x4()
        val voicePads = layout.filter { it.type == PadType.VOICE }
        val drumPads = layout.filter { it.type == PadType.DRUM }
        assertEquals(8, voicePads.size)
        assertEquals(3, drumPads.size)
    }

    @Test
    fun `voice pads have indices 0 through 7`() {
        val layout = DefaultPadLayouts.grid4x4()
        val indices = layout.filter { it.type == PadType.VOICE }.mapNotNull { it.voiceIndex }.sorted()
        assertEquals((0..7).toList(), indices)
    }

    @Test
    fun `drum pads have types 0 1 2`() {
        val layout = DefaultPadLayouts.grid4x4()
        val types = layout.filter { it.type == PadType.DRUM }.mapNotNull { it.drumType }.sorted()
        assertEquals(listOf(0, 1, 2), types)
    }

    @Test
    fun `all pad bounds are within 0 to 1`() {
        val layout = DefaultPadLayouts.grid4x4()
        for (pad in layout) {
            assertTrue(pad.bounds.left >= 0f && pad.bounds.left <= 1f, "left: ${pad.id}")
            assertTrue(pad.bounds.top >= 0f && pad.bounds.top <= 1f, "top: ${pad.id}")
            assertTrue(pad.bounds.right >= 0f && pad.bounds.right <= 1f, "right: ${pad.id}")
            assertTrue(pad.bounds.bottom >= 0f && pad.bounds.bottom <= 1f, "bottom: ${pad.id}")
        }
    }

    @Test
    fun `no pads overlap`() {
        val layout = DefaultPadLayouts.grid4x4()
        for (i in layout.indices) {
            for (j in i + 1 until layout.size) {
                val a = layout[i].bounds
                val b = layout[j].bounds
                val overlaps = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
                assertTrue(!overlaps, "Pads ${layout[i].id} and ${layout[j].id} overlap")
            }
        }
    }
}
