package org.balch.orpheus.features.voice.ui

import org.balch.orpheus.core.audio.EngineParam
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuoKnobLabelsTest {

    @Test
    fun vaLabels() {
        val h = duoKnobLabel(OrpheusEngineId.VA, EngineParam.HARMONICS, 0.5f)
        val t = duoKnobLabel(OrpheusEngineId.VA, EngineParam.TIMBRE, 0.5f)
        val m = duoKnobLabel(OrpheusEngineId.VA, EngineParam.MORPH, 0.5f)
        assertEquals(listOf("detune", "square", "saw"), listOf(h.short, t.short, m.short))
        assertEquals(listOf("≡", "♯", "ψ"), listOf(h.glyph, t.glyph, m.glyph))
        assertEquals("square shape", t.long)
        assertTrue(listOf(h, t, m).all { it.active && !it.locked })
    }

    @Test
    fun dxLocksHarmonicsWithPatchName() {
        val label = duoKnobLabel(OrpheusEngineId.DX2, EngineParam.HARMONICS, (0 + 0.5f) / 32.64f)
        assertEquals("E piano 1", label.short)
        assertEquals("DX2 · E piano 1", label.long)
        assertEquals("≡", label.glyph)
        assertTrue(label.locked)
        assertTrue(label.active)
    }

    @Test
    fun dxTimbreAndMorphStayUnlocked() {
        val t = duoKnobLabel(OrpheusEngineId.DX2, EngineParam.TIMBRE, 0.1f)
        val m = duoKnobLabel(OrpheusEngineId.DX2, EngineParam.MORPH, 0.1f)
        assertEquals("level", t.short)
        assertEquals("env", m.short)
        assertFalse(t.locked || m.locked)
    }

    @Test
    fun oscPanelMorphIsDetune() {
        val label = duoKnobLabel(OrpheusEngineId.OSC, EngineParam.MORPH, 0.5f)
        assertEquals("detune", label.short)
        assertEquals("duo detune, ±25 cents", label.long)
        assertTrue(label.active)
    }

    @Test
    fun spkMorphInactive() {
        val label = duoKnobLabel(OrpheusEngineId.SPK, EngineParam.MORPH, 0.5f)
        assertEquals("-", label.short)
        assertFalse(label.active)
        assertFalse(label.locked)
        assertEquals("ψ", label.glyph)
    }

    @Test
    fun dxThenVaUnlocks() {
        val harmonics = (5 + 0.5f) / 32.64f
        assertTrue(duoKnobLabel(OrpheusEngineId.DX, EngineParam.HARMONICS, harmonics).locked)
        val va = duoKnobLabel(OrpheusEngineId.VA, EngineParam.HARMONICS, harmonics)
        assertFalse(va.locked)
        assertEquals("detune", va.short)
    }

    @Test
    fun mstIsInactiveOnThePanel() {
        val label = duoKnobLabel(OrpheusEngineId.MST, EngineParam.HARMONICS, 0.5f)
        assertEquals("-", label.short)
        assertFalse(label.active)
    }
}
