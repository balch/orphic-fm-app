package org.balch.orpheus.plugins.cowbell

import kotlin.test.Test
import kotlin.test.assertEquals

class CowbellStrikeTest {

    @Test
    fun `full velocity matches the native constant`() {
        assertEquals(0x4007F.toFloat(), cowbellStrikeValue(1f))
    }

    @Test
    fun `kind and color pack into their fields`() {
        assertEquals(0x4E040.toFloat(), cowbellStrikeValue(0.5f, 3, 64))
    }

    @Test
    fun `zero velocity still encodes vel7 1`() {
        assertEquals((0x40000 or 1).toFloat(), cowbellStrikeValue(0f))
    }

    @Test
    fun `negative velocity encodes vel7 1`() {
        assertEquals((0x40000 or 1).toFloat(), cowbellStrikeValue(-3f))
    }

    @Test
    fun `velocity above one clamps to vel7 127`() {
        assertEquals((0x40000 or 127).toFloat(), cowbellStrikeValue(7f))
    }

    @Test
    fun `positive infinity encodes vel7 127`() {
        assertEquals((0x40000 or 127).toFloat(), cowbellStrikeValue(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `NaN encodes vel7 1`() {
        assertEquals((0x40000 or 1).toFloat(), cowbellStrikeValue(Float.NaN))
    }

    @Test
    fun `negative infinity encodes vel7 1`() {
        assertEquals((0x40000 or 1).toFloat(), cowbellStrikeValue(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `kind 16 and color 200 clamp to their maxima`() {
        assertEquals(cowbellStrikeValue(1f, 15, 127), cowbellStrikeValue(1f, 16, 200))
        assertEquals(cowbellStrikeValue(1f, 0, 0), cowbellStrikeValue(1f, -4, -9))
    }

    @Test
    fun `velocity rounds half away from zero like lroundf`() {
        // 0.5 * 127 = 63.5 -> 64, 62.5/127 -> 63
        assertEquals((0x40000 or 64).toFloat(), cowbellStrikeValue(0.5f))
        assertEquals((0x40000 or 63).toFloat(), cowbellStrikeValue(62.5f / 127f))
    }
}
