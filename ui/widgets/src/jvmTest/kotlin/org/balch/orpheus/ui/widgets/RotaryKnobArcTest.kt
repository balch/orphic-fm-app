package org.balch.orpheus.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class RotaryKnobArcTest {

    @Test
    fun unipolarFillsFromTheDialStart() {
        assertEquals(135f to 0f, progressArc(0f, bipolar = false, startAngle = 135f, sweepAngle = 270f))
        assertEquals(135f to 135f, progressArc(0.5f, bipolar = false, startAngle = 135f, sweepAngle = 270f))
        assertEquals(135f to 270f, progressArc(1f, bipolar = false, startAngle = 135f, sweepAngle = 270f))
    }

    @Test
    fun bipolarRunsFromTwelveOClock() {
        assertEquals(270f to 0f, progressArc(0.5f, bipolar = true, startAngle = 135f, sweepAngle = 270f))
        assertEquals(270f to 135f, progressArc(1f, bipolar = true, startAngle = 135f, sweepAngle = 270f))
        assertEquals(270f to -135f, progressArc(0f, bipolar = true, startAngle = 135f, sweepAngle = 270f))
    }
}
