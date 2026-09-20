package org.balch.orpheus.features.visualizations.viz.face

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorphCurvesTest {
    private val samples = (0..100).map { it / 100f }

    @Test fun `shape runs from zero to one`() {
        assertEquals(0f, shape(0f), 1e-4f)
        assertEquals(1f, shape(1f), 1e-4f)
    }

    @Test fun `shape never decreases`() {
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(shape(b) >= shape(a) - 1e-6f, "shape fell between $a and $b")
        }
    }

    @Test fun `surge is never negative`() {
        for (i in samples) for (f in samples) assertTrue(surge(i, f) >= 0f, "surge($i, $f) < 0")
    }

    @Test fun `silence adds nothing and a finished face cannot go further`() {
        for (f in samples) assertEquals(0f, surge(0f, f), 1e-4f)
        for (i in samples) assertEquals(0f, surge(i, 1f), 1e-4f)
    }

    @Test fun `louder never surges less`() {
        for (f in samples) samples.zipWithNext().forEach { (a, b) ->
            assertTrue(surge(b, f) >= surge(a, f) - 1e-6f, "surge fell with intensity at floor $f")
        }
    }
}
