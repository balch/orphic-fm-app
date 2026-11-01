package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.balch.orpheus.plugins.cowbell.CowbellMode
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BellMorphTest {

    private val tap = BellCamera.forMode(CowbellMode.TAP)
    private val synth = BellCamera.forMode(CowbellMode.SYNTH)
    private val projector = BellProjector(Size(320f, 330f), tap)

    private fun assertCameraEquals(expected: BellCamera, actual: BellCamera, eps: Float = 1e-4f) {
        assertEquals(expected.pitch, actual.pitch, eps)
        assertEquals(expected.yaw, actual.yaw, eps)
        assertEquals(expected.roll, actual.roll, eps)
        assertEquals(expected.zoom, actual.zoom, eps)
    }

    private fun liquid(p: Float, t: Float = 0.3f): LiquidFrame = liquidFrame(projector, tap, p, t)

    private fun radii(frame: LiquidFrame): List<Float> = List(8) { frame.drops[it * 4 + 2] }

    @Test
    fun `progress is time based`() {
        val transition = BellTransition(tap, synth, startSeconds = 10.0, durationSeconds = LIQUID_SECONDS)
        // Sampling early or often changes nothing: progress is a pure function of the clock.
        transition.progress(10.3)
        transition.progress(10.9)
        assertEquals(0.5f, transition.progress(11.1), 1e-5f)
        assertEquals(1f, transition.progress(12.3))
        assertTrue(transition.isDone(12.3))
        assertTrue(!transition.isDone(11.1))
        assertEquals(0f, transition.progress(9.0))
    }

    @Test
    fun `a transition with no duration is done at once`() {
        listOf(0.0, -1.0).forEach { duration ->
            val transition = BellTransition(tap, synth, startSeconds = 3.0, durationSeconds = duration)
            assertEquals(1f, transition.progress(3.0))
            assertTrue(transition.isDone(2.0))
            assertCameraEquals(synth, transition.camera(3.0))
        }
    }

    @Test
    fun `camera starts and ends on the views`() {
        val transition = BellTransition(tap, synth, 5.0, PLAIN_TURN_SECONDS)
        assertCameraEquals(tap, transition.camera(5.0))
        assertCameraEquals(synth, transition.camera(5.0 + PLAIN_TURN_SECONDS))
    }

    @Test
    fun `a switch mid morph starts from where the bell is`() {
        val first = BellTransition(tap, synth, 20.0, LIQUID_SECONDS)
        val sampleAt = 20.0 + LIQUID_SECONDS * 0.4
        val sample = first.camera(sampleAt)
        val back = BellTransition(sample, tap, sampleAt, LIQUID_SECONDS)
        assertCameraEquals(sample, back.camera(sampleAt))
    }

    @Test
    fun `mesh shows at both ends and hides in the middle`() {
        assertEquals(1f, meshAlpha(0f))
        assertEquals(0f, meshAlpha(0.5f))
        assertEquals(1f, meshAlpha(1f))
    }

    @Test
    fun `drops are absent at rest`() {
        assertTrue(radii(liquid(0f)).all { it == 0f })
        assertTrue(radii(liquid(1f)).all { it == 0f })
    }

    @Test
    fun `drops are out mid morph`() {
        assertTrue(radii(liquid(0.4f)).count { it > 0f } >= 6)
    }

    @Test
    fun `the hull is convex and capped`() {
        val random = Random(7)
        repeat(20) {
            val cloud = List(30) { Offset(random.nextFloat() * 300f, random.nextFloat() * 300f) }
            val hull = convexHull(cloud)
            assertTrue(hull.size in 3..30)
            val signs = hull.indices.map { i ->
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val c = hull[(i + 2) % hull.size]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            }
            assertTrue(signs.all { it > 0f } || signs.all { it < 0f })
        }
        for (step in 0..20) {
            assertTrue(liquid(step / 20f).polyCount <= 16)
        }
    }

    @Test
    fun `the pool springs back past full`() {
        assertTrue(poolScale(0.4f) < 1f)
        val overshoot = (60..90).map { poolScale(it / 100f) }.max()
        assertTrue(overshoot > 1f, "max scale was $overshoot")
        assertTrue(abs(poolScale(0f) - 1f) < 1e-6f)
    }
}
