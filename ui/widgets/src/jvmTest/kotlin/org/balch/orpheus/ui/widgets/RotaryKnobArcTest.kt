package org.balch.orpheus.ui.widgets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

    @Test
    fun gradientRunsAlongTheDialWithNoSeamAtThreeOClock() {
        val from = Color.Red.copy(alpha = 0.5f)
        val to = Color.Red
        val stops = dialGradientStops(from, to, startAngle = 135f, sweepAngle = 270f)
        val atThree = lerp(from, to, 225f / 270f)
        assertEquals(listOf(0f to atThree, 0.125f to to, 0.375f to from, 1f to atThree), stops.toList())
    }

    @Test
    fun gradientForADialThatDoesNotWrap() {
        val stops = dialGradientStops(Color.Black, Color.White, startAngle = 90f, sweepAngle = 180f)
        assertEquals(listOf(0.25f to Color.Black, 0.75f to Color.White), stops.toList())
    }
}
