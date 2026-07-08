package org.balch.orpheus.features.visualizations.viz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumBallisticsTest {
    @Test fun `magnitude at or below floor maps to zero`() {
        assertEquals(0f, magnitudeToHeight(0f), 1e-4f)
    }

    @Test fun `unity magnitude maps to full height`() {
        assertEquals(1f, magnitudeToHeight(1f), 1e-4f) // 20*log10(1) = 0 dB = ceil
    }

    @Test fun `tilt lifts high bands and leaves the lowest band unchanged`() {
        val low = applyTilt(1f, 0, 40, tiltKnob = 1f)
        val high = applyTilt(1f, 39, 40, tiltKnob = 1f)
        assertEquals(1f, low, 1e-4f)
        assertTrue(high > low, "top band should be boosted above the bottom band")
    }

    @Test fun `ballistics attack is instant`() {
        val b = SpectrumBallistics(3)
        b.update(floatArrayOf(1f, 1f, 1f), decayKnob = 0.5f)
        assertEquals(1f, b.heights[0], 1e-4f)
    }

    @Test fun `ballistics decay falls gradually and peak-hold falls slower`() {
        val b = SpectrumBallistics(1)
        b.update(floatArrayOf(1f), decayKnob = 0.5f)   // jump up
        b.update(floatArrayOf(0f), decayKnob = 0.5f)   // start falling
        assertTrue(b.heights[0] in 0.01f..0.99f, "height should fall partway, not snap to 0")
        assertTrue(b.peaks[0] > b.heights[0], "peak-hold cap should lag above the bar")
    }
}
