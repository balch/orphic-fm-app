package org.balch.orpheus.core.gestures

import kotlin.test.Test
import kotlin.test.assertEquals

class AslSignTest {
    @Test
    fun `targetDisplayLabel returns voice labels for numbers`() {
        assertEquals("V1", AslSign.NUM_1.targetDisplayLabel)
        assertEquals("V8", AslSign.NUM_8.targetDisplayLabel)
    }

    @Test
    fun `targetDisplayLabel returns system names`() {
        assertEquals("Vibrato", AslSign.LETTER_V.targetDisplayLabel)
        assertEquals("Coupling", AslSign.LETTER_C.targetDisplayLabel)
        assertEquals("Chaos", AslSign.LETTER_Y.targetDisplayLabel)
    }

    @Test
    fun `paramDisplayLabel returns parameter names`() {
        assertEquals("Morph", AslSign.LETTER_M.paramDisplayLabel)
        assertEquals("Sharp", AslSign.LETTER_S.paramDisplayLabel)
        assertEquals("Bend", AslSign.LETTER_B.paramDisplayLabel)
        assertEquals("ModLvl", AslSign.LETTER_L.paramDisplayLabel)
        assertEquals("Volume", AslSign.LETTER_W.paramDisplayLabel)
    }

    @Test
    fun `paramDisplayLabel is null for non-parameter signs`() {
        assertEquals(null, AslSign.NUM_1.paramDisplayLabel)
        assertEquals(null, AslSign.LETTER_V.paramDisplayLabel)
    }

    @Test
    fun `duoDisplayLabel formats correctly`() {
        assertEquals("D1", AslSign.duoDisplayLabel(0))
        assertEquals("D4", AslSign.duoDisplayLabel(3))
    }

    @Test
    fun `quadDisplayLabel formats correctly`() {
        assertEquals("Q1", AslSign.quadDisplayLabel(0))
        assertEquals("Q2", AslSign.quadDisplayLabel(1))
    }
}
