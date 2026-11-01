package org.balch.orpheus.features.cowbell.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class TiltMappingTest {
    private fun assertPair(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-6f, "x")
        assertEquals(expected.second, actual.second, 1e-6f, "y")
    }

    /** Gravity for a phone tipped [degrees] from flat, top edge rising toward the player. */
    private fun pitched(degrees: Float): MotionSample {
        val r = degrees * PI.toFloat() / 180f
        return MotionSample().apply {
            gravityX = 0f
            gravityY = -sin(r)
            gravityZ = cos(r)
        }
    }

    @Test
    fun tiltFollowsDisplayRotation() {
        assertPair(1f to 0f, displayAxes(1f, 0f, 0))
        assertPair(0f to -1f, displayAxes(1f, 0f, 1))
        assertPair(-1f to 0f, displayAxes(1f, 0f, 2))
        assertPair(0f to 1f, displayAxes(1f, 0f, 3))
    }

    @Test
    fun displayAxesKeepsTheTopEdgeOnTheSameSide() {
        // The device top edge (+y) points up on screen at rotation 0, left at 1, down at 2, right at 3.
        assertPair(0f to -1f, displayAxes(0f, 1f, 0))
        assertPair(-1f to 0f, displayAxes(0f, 1f, 1))
        assertPair(0f to 1f, displayAxes(0f, 1f, 2))
        assertPair(1f to 0f, displayAxes(0f, 1f, 3))
    }

    @Test
    fun rotationWrapsModuloFour() {
        assertPair(displayAxes(1f, 0f, 1), displayAxes(1f, 0f, 5))
        assertPair(displayAxes(1f, 0f, 3), displayAxes(1f, 0f, -1))
    }

    @Test
    fun pitchIsRelativeToNeutral() {
        val neutral = TiltNeutral(samples = 1)
        neutral.capture(pitched(40f))
        assertEquals(0f, neutral.pitchDegrees(pitched(40f)), 0.01f)
        assertEquals(35f, neutral.pitchDegrees(pitched(75f)), 0.01f)
        assertEquals(-25f, neutral.pitchDegrees(pitched(15f)), 0.01f)
        assertEquals(0f, neutral.rollDegrees(pitched(75f)), 0.01f)
    }

    @Test
    fun rollIsRelativeToNeutral() {
        val neutral = TiltNeutral(samples = 1)
        val held = MotionSample().apply { gravityX = sin(0.2f); gravityZ = cos(0.2f) }
        neutral.capture(held)
        val tipped = MotionSample().apply { gravityX = sin(0.2f + 0.3f); gravityZ = cos(0.2f + 0.3f) }
        assertEquals(0.3f * 180f / PI.toFloat(), neutral.rollDegrees(tipped), 0.01f)
    }

    @Test
    fun neutralResetForgetsTheCapturedPose() {
        val neutral = TiltNeutral(samples = 1)
        neutral.capture(pitched(40f))
        neutral.reset()
        assertEquals(false, neutral.captured)
        assertEquals(40f, neutral.pitchDegrees(pitched(40f)), 0.01f)
    }

    @Test
    fun pitchWrapsAcrossTheSeam() {
        val neutral = TiltNeutral(samples = 1)
        neutral.capture(pitched(170f))
        assertEquals(20f, neutral.pitchDegrees(pitched(190f)), 0.01f)
    }

    @Test
    fun neutralIsTheAverageOfTheFirstEightSamples() {
        val neutral = TiltNeutral()
        // A settling hand: alternating 8 and 12 degrees around a 10 degree hold.
        for (i in 0 until 7) {
            neutral.capture(pitched(if (i % 2 == 0) 8f else 12f))
            assertEquals(false, neutral.captured, "after ${i + 1} samples")
        }
        neutral.capture(pitched(12f))
        assertEquals(true, neutral.captured)
        assertEquals(0f, neutral.pitchDegrees(pitched(10f)), 0.05f)
        // Samples after the capture do not move it.
        neutral.capture(pitched(40f))
        assertEquals(0f, neutral.pitchDegrees(pitched(10f)), 0.05f)
        neutral.reset()
        assertEquals(false, neutral.captured)
    }

    @Test
    fun structureOffsetFromPitchIsClampedAndFinite() {
        assertEquals(0.4f, structureOffsetFromPitch(35f), 1e-6f)
        assertEquals(-0.4f, structureOffsetFromPitch(-70f), 1e-6f)
        assertEquals(0.2f, structureOffsetFromPitch(17.5f), 1e-6f)
        assertEquals(0f, structureOffsetFromPitch(Float.NaN), 0f)
    }
}
