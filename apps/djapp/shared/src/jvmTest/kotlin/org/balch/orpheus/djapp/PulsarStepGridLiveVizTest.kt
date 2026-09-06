package org.balch.orpheus.djapp

import com.sun.management.ThreadMXBean
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Behavioural tests for PulsarStepGrid, driven through [GridScene] the way the app drives it:
 * a newly allocated PulsarVizData per emission, frame clock stepped by hand.
 */
class PulsarStepGridLiveVizTest {

    /**
     * The grid's frame loop must act on the emission in front of it, not the one it was born
     * with. Track levels are the observable: the loop smooths them, and the smoothed value
     * drives the playhead cell's glow halo, its fill alpha and its size pulse. Two scenes start
     * identically idle and then differ only in the levels they emit, so if the loop reads live
     * data one grows a lit playhead column and the other cannot.
     *
     * This replaced an equivalent test on spark particles when those were removed. It guards
     * the same defect: a LaunchedEffect(Unit) that captures its first vizData renders both
     * scenes identically, because neither ever leaves the idle emission behind.
     */
    @Test
    fun frameLoopSmoothsLevelsFromFreshlyAllocatedVizData() {
        val lit = settledFrame(trackLevel = 0.9f)
        val dark = settledFrame(trackLevel = 0f)
        val differing = countDifferingPixels(lit, dark)
        assertTrue(
            differing > 200,
            "expected the playhead column to light up; only $differing pixels differ from the silent control",
        )
    }

    /**
     * Nothing in the grid may repaint most of the canvas as the playhead advances. A whole-grid
     * transform does exactly that, and at high energy, where a step lands every few frames, it
     * reads as the screen shimmering.
     *
     * Measured: with the 3% beat-pulse scale on the outer graphicsLayer, a step advance changed
     * 84% of the canvas and the frame after it changed another 83%. Without it, a step advance
     * changes 13.8% (the playhead column moving one cell) and the next frame ~0%. The bar sits
     * between the two.
     */
    @Test
    fun noFrameRepaintsMostOfTheCanvasDuringPlayback() {
        val g = GridScene(856, 290)
        try {
            g.render(0, playhead = -1).close()
            var ms = 0L
            var ph = 0
            var prev: BufferedImage? = null
            var worst = 0.0
            var worstFrame = -1
            // A step every 3 frames is a very high energy song at 60fps.
            repeat(24) { i ->
                ms += 16
                if (i % 3 == 0) ph = (ph + 1) % 16
                val img = g.render(ms, ph, trackLevel = 0.9f).use { it.toBuffered() }
                prev?.let {
                    val pct = 100.0 * countDifferingPixels(it, img) / (img.width * img.height)
                    if (pct > worst) { worst = pct; worstFrame = i }
                }
                prev = img
            }
            assertTrue(
                worst < 40.0,
                "frame $worstFrame repainted %.1f%% of the canvas; a playhead step should change ~14%%".format(worst),
            )
        } finally {
            g.close()
        }
    }

    /**
     * A grid that has never played has nothing to animate, so after its frames settle nothing
     * in the scene may still be waiting on the frame clock. A perpetual withFrameNanos loop
     * keeps that flag true forever, which on Android keeps the Choreographer subscribed and
     * the display pipeline awake while the app sits idle.
     */
    @Test
    fun frameLoopSleepsWhileIdle() {
        val g = GridScene(856, 290)
        try {
            repeat(3) { i -> g.render(16L * i, playhead = -1).close() }
            assertTrue(!g.hasInvalidations(), "an idle grid still has a frame pending")
        } finally {
            g.close()
        }
    }

    /**
     * While playing the loop must be running, and once playback stops it must end rather than
     * spin on forever. The beat pulse can still be settling for a frame or two after the stop,
     * so this allows half a second of frames before requiring the scene to be quiet.
     */
    @Test
    fun frameLoopSleepsAfterPlaybackStops() {
        val g = GridScene(856, 290)
        try {
            g.render(0, playhead = -1).close()
            repeat(4) { i -> g.render(16L * (i + 1), playhead = 8, trackLevel = 0.9f).close() }
            assertTrue(g.hasInvalidations(), "the grid is playing; the frame loop should be running")

            var ms = 80L
            repeat(30) { ms += 16; g.render(ms, playhead = -1).close() }
            assertTrue(!g.hasInvalidations(), "half a second after playback stopped a frame is still pending")
        } finally {
            g.close()
        }
    }

    /**
     * One frame of the grid during playback must stay under 60 KB of JVM allocation. With the
     * cell gradients built inline it was ~126 KB (two brushes per lit cell per frame); the
     * budget is well under half so a reintroduced per-cell allocation fails it while JIT and
     * Compose-version noise (measured at +/-0.1 KB) cannot.
     */
    @Test
    fun gridFrameAllocatesUnderBudget() {
        val mx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().id
        val g = GridScene(856, 290)
        try {
            var ms = 0L
            var ph = 8
            var idx = 0
            fun tick() {
                ms += 16; idx++
                if (idx % 6 == 0) ph = (ph + 1) % 16   // ~150 BPM sixteenths
                g.render(ms, ph, trackLevel = 0.9f).close()
            }
            repeat(60) { tick() }
            System.gc(); Thread.sleep(50)
            val frames = 120
            val before = mx.getThreadAllocatedBytes(tid)
            repeat(frames) { tick() }
            val perFrame = (mx.getThreadAllocatedBytes(tid) - before) / frames
            assertTrue(perFrame < 60_000, "grid frame allocated $perFrame bytes, budget is 60000")
        } finally {
            g.close()
        }
    }

    /** Starts idle, then emits [trackLevel] for long enough that the loop's smoothing settles. */
    private fun settledFrame(trackLevel: Float): BufferedImage {
        val g = GridScene(856, 290)
        try {
            g.render(0, playhead = -1).close()
            var ms = 0L
            repeat(9) { ms += 16; g.render(ms, playhead = 8, trackLevel = trackLevel).close() }
            ms += 16
            return g.render(ms, playhead = 8, trackLevel = trackLevel).use { it.toBuffered() }
        } finally {
            g.close()
        }
    }

    private fun Image.toBuffered(): BufferedImage =
        ImageIO.read(ByteArrayInputStream(encodeToData()!!.bytes))

    private fun countDifferingPixels(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getRGB(x, y) != b.getRGB(x, y)) n++
        }
        return n
    }
}
