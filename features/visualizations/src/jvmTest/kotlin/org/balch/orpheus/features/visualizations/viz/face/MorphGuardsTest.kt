package org.balch.orpheus.features.visualizations.viz.face

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the NaN/Inf/out-of-range guards in isolation; must not call shape()/surge(). */
class MorphGuardsTest {
    @Test fun `ratchet ignores a non-finite shaped value and keeps the floor`() {
        assertEquals(0.4f, ratchet(0.4f, Float.NaN))
        assertEquals(0.4f, ratchet(0.4f, Float.POSITIVE_INFINITY))
        assertEquals(0.4f, ratchet(0.4f, Float.NEGATIVE_INFINITY))
    }

    @Test fun `ratchet clamps a finite shaped value and never falls`() {
        assertEquals(0.4f, ratchet(0.4f, -1f))
        assertEquals(1f, ratchet(0.4f, 2f))
        assertEquals(0.6f, ratchet(0.4f, 0.6f))
    }

    @Test fun `surged ignores a non-finite surge and keeps the base`() {
        assertEquals(0.4f, surged(0.4f, Float.NaN))
        assertEquals(0.4f, surged(0.4f, Float.POSITIVE_INFINITY))
        assertEquals(0.4f, surged(0.4f, Float.NEGATIVE_INFINITY))
    }

    @Test fun `surged clamps a finite surge into 0 to 1`() {
        assertEquals(0f, surged(0.4f, -1f))
        assertEquals(1f, surged(0.9f, 2f))
        assertEquals(0.6f, surged(0.4f, 0.2f))
    }
}
