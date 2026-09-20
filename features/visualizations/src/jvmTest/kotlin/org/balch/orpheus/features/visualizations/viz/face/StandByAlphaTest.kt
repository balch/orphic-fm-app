package org.balch.orpheus.features.visualizations.viz.face

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The contract of the stand-by card's fade, whatever curve ends up inside it. */
class StandByAlphaTest {
    private val steps = (0..20).map { it / 20f }

    @Test fun `gone in any mono frame`() {
        steps.forEach { m -> steps.forEach { e -> assertEquals(0f, standByAlpha(m, e, mono = 1f)) } }
    }

    @Test fun `gone at and past the permanent mono mark`() {
        steps.forEach { e ->
            assertEquals(0f, standByAlpha(MorphDirector.MONO_PERMANENT, e, mono = 0f))
            assertEquals(0f, standByAlpha(1f, e, mono = 0f))
        }
    }

    @Test fun `shows at the top of a quiet song`() {
        assertTrue(standByAlpha(0f, 0f, mono = 0f) > 0.5f)
    }

    @Test fun `always within 0 to 1`() {
        steps.forEach { m -> steps.forEach { e -> assertTrue(standByAlpha(m, e, 0f) in 0f..1f, "m=$m e=$e") } }
    }

    @Test fun `never comes back as the turn advances at a fixed energy`() {
        steps.forEach { e ->
            steps.zipWithNext().forEach { (a, b) ->
                assertTrue(standByAlpha(b, e, 0f) <= standByAlpha(a, e, 0f), "e=$e morph $a -> $b")
            }
        }
    }

    @Test fun `non-finite inputs cannot show the card past its life or leave 0 to 1`() {
        assertEquals(0f, standByAlpha(Float.NaN, 0.5f, 0f))
        assertEquals(0f, standByAlpha(Float.POSITIVE_INFINITY, 0.5f, 0f))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { e ->
            assertTrue(standByAlpha(0.2f, e, 0f) in 0f..1f)
        }
    }
}
