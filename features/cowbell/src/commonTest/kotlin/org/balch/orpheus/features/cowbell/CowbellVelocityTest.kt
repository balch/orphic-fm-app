package org.balch.orpheus.features.cowbell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CowbellVelocityTest {

    @Test
    fun velocityFromPointerSpeed() {
        assertEquals(0.6f, velocityFromPointerSpeed(0f), 1e-6f)
        assertEquals(0.8f, velocityFromPointerSpeed(750f), 1e-6f)
        assertEquals(1.0f, velocityFromPointerSpeed(5000f), 1e-6f)
    }

    @Test
    fun badSpeedsStayInRange() {
        assertEquals(0.6f, velocityFromPointerSpeed(-300f), 1e-6f)
        assertEquals(0.6f, velocityFromPointerSpeed(Float.NaN), 1e-6f)
        assertEquals(1.0f, velocityFromPointerSpeed(Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun fusionPrefersTheStrongerSignal() {
        assertEquals(0.795f, fusedVelocity(0.2f, 0.9f)!!, 0.01f)
        assertEquals(0.6f, fusedVelocity(0.6f, null)!!, 1e-6f)
        assertEquals(0.4f, fusedVelocity(null, 0.4f)!!, 1e-6f)
        assertNull(fusedVelocity(null, null))
    }

    @Test
    fun fusionStaysInRangeAtTheEnds() {
        assertEquals(0f, fusedVelocity(0f, 0f)!!, 1e-6f)
        assertEquals(1f, fusedVelocity(1f, 1f)!!, 1e-6f)
    }

    @Test
    fun tapVelocityRunsFromFloorToFull() {
        assertEquals(0.45f, tapVelocity(0f, 0f), 1e-6f)
        assertEquals(1f, tapVelocity(1f, 0f), 1e-6f)
        // Nothing measured: the pointer's speed decides, as before.
        assertEquals(velocityFromPointerSpeed(750f), tapVelocity(null, 750f), 1e-6f)
    }

    @Test
    fun joltNormRange() {
        assertEquals(0f, joltNorm(0.15f), 1e-6f)
        assertEquals(1f, joltNorm(3.0f), 1e-6f)
        assertEquals(0f, joltNorm(Float.NaN), 1e-6f)
        assertEquals(0f, joltNorm(0f), 1e-6f)
        assertEquals(1f, joltNorm(40f), 1e-6f)
        assertEquals(0.5f, joltNorm(1.575f), 1e-4f)
    }

    @Test
    fun aJoltUnderTheFloorIsNoJolt() {
        assertNull(joltOrNull(0f))
        assertNull(joltOrNull(0.15f))
        assertNull(joltOrNull(Float.NaN))
        assertEquals(0.5f, joltOrNull(1.575f)!!, 1e-4f)
        assertEquals(1f, joltOrNull(40f)!!, 1e-6f)
        // With a contact size the tap follows it, and with neither it follows the pointer's speed.
        assertEquals(0.4f, fusedVelocity(joltOrNull(0f), 0.4f)!!, 1e-6f)
        assertEquals(0.6f, tapVelocity(fusedVelocity(joltOrNull(0f), null), 0f), 1e-6f)
    }

    @Test
    fun contactCalibrationNeedsFour() {
        val cal = ContactCalibration()
        assertNull(cal.norm(10f))
        assertNull(cal.norm(20f))
        assertNull(cal.norm(30f))
        val fourth = assertNotNull(cal.norm(40f))
        assertTrue(fourth in 0f..1f, "norm $fourth")
        assertEquals(1f, fourth, 1e-6f)
    }

    @Test
    fun contactCalibrationNeedsASpread() {
        val cal = ContactCalibration()
        repeat(6) { assertNull(cal.norm(12f)) }
        // 0.5 px of spread is still noise.
        assertNull(cal.norm(12.5f))
        assertNotNull(cal.norm(14f))
    }

    @Test
    fun contactCalibrationIgnoresUnreportedSizes() {
        val cal = ContactCalibration()
        repeat(8) { assertNull(cal.norm(0f)) }
        assertNull(cal.norm(Float.NaN))
        // Zeros and NaNs were never counted as touches.
        assertNull(cal.norm(10f))
    }

    @Test
    fun contactCalibrationForgetsTouchesPastThirtyTwo() {
        val cal = ContactCalibration()
        cal.norm(100f)
        repeat(32) { cal.norm(10f + (it % 2)) }
        // The 100 has aged out, so 11 is the top of the range.
        assertEquals(1f, cal.norm(11f)!!, 1e-6f)
    }
}
