package org.balch.orpheus.features.dj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaderVelocityTest {

    @Test
    fun centerGivesZero() {
        assertEquals(0f, faderVelocity(0f))
    }

    @Test
    fun fullRightGivesPositiveTen() {
        assertEquals(10f, faderVelocity(1f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun fullLeftGivesNegativeTen() {
        assertEquals(-10f, faderVelocity(-1f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun quadraticCurveAtHalf() {
        // 0.5² * 10 = 2.5
        assertEquals(2.5f, faderVelocity(0.5f), absoluteTolerance = 0.0001f)
        assertEquals(-2.5f, faderVelocity(-0.5f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun quadraticCurveAtThreeQuarters() {
        // 0.75² * 10 = 5.625
        assertEquals(5.625f, faderVelocity(0.75f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun inputIsClampedOutsideRange() {
        assertEquals(10f, faderVelocity(1.5f), absoluteTolerance = 0.0001f)
        assertEquals(-10f, faderVelocity(-2f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun signMatchesInput() {
        for (x in listOf(-0.9f, -0.3f, -0.05f, 0.05f, 0.3f, 0.9f)) {
            val v = faderVelocity(x)
            assertTrue(
                (x > 0f && v > 0f) || (x < 0f && v < 0f),
                "sign mismatch: fx=$x -> v=$v",
            )
        }
    }
}
