package org.balch.orpheus.features.cowbell.motion

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionAccumulatorTest {
    @Test
    fun kicksSumUntilDrainedThenStartOver() {
        val acc = MotionAccumulator()
        val out = MotionSample()
        acc.addKick(1f, 2f)
        acc.addKick(0.5f, -4f)
        acc.drain(out, 0)
        assertEquals(1.5f, out.kickX, 1e-6f)
        // Device y up is screen y down at rotation 0.
        assertEquals(2f, out.kickY, 1e-6f)
        acc.drain(out, 0)
        assertEquals(0f, out.kickX, 1e-6f)
        assertEquals(0f, out.kickY, 1e-6f)
    }

    @Test
    fun drainRotatesGravityAndKickIntoDisplayAxes() {
        val acc = MotionAccumulator()
        val out = MotionSample()
        acc.publishGravity(1f, 0f, 0f)
        acc.addKick(3f, 0f)
        acc.drain(out, 1)
        assertEquals(0f, out.gravityX, 1e-6f)
        assertEquals(-1f, out.gravityY, 1e-6f)
        assertEquals(0f, out.kickX, 1e-6f)
        assertEquals(-3f, out.kickY, 1e-6f)
    }

    @Test
    fun resetReturnsToRest() {
        val acc = MotionAccumulator()
        val out = MotionSample()
        acc.publishGravity(0.3f, 0.4f, 0.5f)
        acc.addKick(9f, 9f)
        acc.reset()
        acc.drain(out, 0)
        assertEquals(0f, out.gravityX, 1e-6f)
        assertEquals(1f, out.gravityZ, 1e-6f)
        assertEquals(0f, out.kickX, 1e-6f)
    }
}
