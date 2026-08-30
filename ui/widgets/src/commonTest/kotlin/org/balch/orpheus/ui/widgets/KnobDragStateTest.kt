package org.balch.orpheus.ui.widgets

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the D-pad stepping arithmetic. The key routing that feeds it can only be checked in
 * the running app; this pins the part that can be tested deterministically.
 */
class KnobDragStateTest {

    private fun state(value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f) =
        KnobDragState(initialValue = value, range = range, sensitivity = 200f)

    @Test
    fun oneStepMovesTwoAndAHalfPercentOfRange() {
        val s = state(0.5f)
        val next = s.applyStep(1)
        assertTrue(next != null && abs(next - 0.525f) < 1e-5f, "expected 0.525 but was $next")
    }

    @Test
    fun stepsScaleWithTheRangeNotThePixelSize() {
        // A knob over 0..200 must move 40x further per press than one over 0..5, so a press
        // means the same proportion of travel on every knob.
        val wide = state(100f, 0f..200f).applyStep(1)!!
        val narrow = state(2.5f, 0f..5f).applyStep(1)!!
        assertTrue(abs((wide - 100f) - 5f) < 1e-3f, "wide range step was ${wide - 100f}")
        assertTrue(abs((narrow - 2.5f) - 0.125f) < 1e-4f, "narrow range step was ${narrow - 2.5f}")
    }

    @Test
    fun negativeStepsGoDown() {
        val next = state(0.5f).applyStep(-1)!!
        assertTrue(next < 0.5f, "a left press must decrease the value")
    }

    @Test
    fun fineStepIsSmallerThanCoarse() {
        val coarse = state(0.5f).applyStep(1)!! - 0.5f
        val fine = state(0.5f).applyStep(1, fine = true)!! - 0.5f
        assertTrue(fine < coarse, "fine=$fine should be smaller than coarse=$coarse")
    }

    @Test
    fun clampsToRangeAndReportsNoChangeAtTheStop() {
        val s = state(1f)
        assertNull(s.applyStep(1), "at the top of the range a further step changes nothing")
        assertEquals(1f, s.internalValue)

        val low = state(0f)
        assertNull(low.applyStep(-1), "at the bottom of the range a further step changes nothing")
    }

    @Test
    fun fortyStepsCrossTheWholeRange() {
        val s = state(0f)
        repeat(40) { s.applyStep(1) }
        assertTrue(abs(s.internalValue - 1f) < 1e-4f, "ended at ${s.internalValue}")
    }
}
