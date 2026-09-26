package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.plugin.viz.ScopeFrame
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scope that publishes whatever window a test hands it, by reference, so publishing allocates nothing. */
internal class TestScopeFrame(override val size: Int = ScopePoints) : ScopeFrame {
    override val windowMs: Float = 40f
    override var version: Int = 0
        private set
    private var window = FloatArray(size)
    private var triggered = false

    fun publish(points: FloatArray, triggered: Boolean) {
        window = points
        this.triggered = triggered
        version++
    }

    override fun readInto(dest: FloatArray): Boolean {
        window.copyInto(dest, endIndex = minOf(size, dest.size))
        return triggered
    }
}

internal const val ScopePoints = 256

/** [cycles] of a sine across the window at [amplitude]. */
internal fun sineWindow(cycles: Float, amplitude: Float) =
    FloatArray(ScopePoints) { amplitude * sin(2 * PI * cycles * it / ScopePoints).toFloat() }

/** A minor triad over 40ms, as 110, 137.5 and 165 Hz would lie. */
internal fun chordWindow(amplitude: Float) =
    FloatArray(ScopePoints) { k -> amplitude * listOf(4.4f, 5.5f, 6.6f).sumOf { sin(2 * PI * it * k / ScopePoints) }.toFloat() / 3f }

internal fun noiseWindow(amplitude: Float, seed: Int) = Random(seed).let { r -> FloatArray(ScopePoints) { amplitude * (r.nextFloat() * 2 - 1) } }

class ScopeTraceTest {
    private val frame = TestScopeFrame()
    private val trace = ScopeTrace()
    private var now = 1_000L

    /** One poll's window, 16ms on the play clock after the last. */
    private fun step(points: FloatArray, triggered: Boolean = true, dtMs: Long = 16): Boolean {
        now += dtMs
        frame.publish(points, triggered)
        return trace.update(frame, now)
    }

    private fun points() = FloatArray(trace.size) { trace[it] }
    private fun peak() = points().maxOf { abs(it) }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var ab = 0f
        var aa = 0f
        var bb = 0f
        for (i in a.indices) {
            ab += (a[i] - ma) * (b[i] - mb)
            aa += (a[i] - ma) * (a[i] - ma)
            bb += (b[i] - mb) * (b[i] - mb)
        }
        return ab / sqrt(aa * bb)
    }

    @Test
    fun aSteadySineDrawsTheSameShapeFrameAfterFrame() {
        // Four whole cycles, so a sample lands on each crest and the window's peak is exactly 0.3.
        val sine = sineWindow(4f, 0.3f)
        repeat(20) { step(sine) }
        val settled = points()
        for (i in sine.indices) assertEquals(sine[i] / 0.3f, settled[i], 1e-3f, "point $i")
        step(sine)
        for (i in sine.indices) assertEquals(settled[i], trace[i], 1e-5f, "a steady tone moved at point $i")
    }

    @Test
    fun aChordHoldsItsShapeAndStaysInsideTheRing() {
        val chord = chordWindow(0.4f)
        repeat(20) {
            step(chord)
            assertTrue(peak() <= 1f, "the chord overdrew: ${peak()}")
        }
        val settled = points()
        step(chord)
        assertTrue(correlation(settled, points()) > 0.9999f, "a steady chord changed shape")
        assertTrue(peak() > 0.95f, "a steady chord never reached full height: ${peak()}")
    }

    @Test
    fun aWindowSeenTwiceIsTakenOnce() {
        assertTrue(step(sineWindow(3f, 0.2f)))
        assertFalse(trace.update(frame, now + 16), "the same version was taken again")
    }

    @Test
    fun silenceDrawsThePlainArc() {
        repeat(30) { step(FloatArray(ScopePoints), triggered = false) }
        assertTrue(points().all { it == 0f }, "silence drew a trace")
    }

    @Test
    fun noiseWithNoTriggerNeverDrawsItsOwnShape() {
        repeat(30) { step(noiseWindow(0.3f, it), triggered = false) }
        assertTrue(points().all { it == 0f }, "untriggered noise drew a trace before any tone")

        val sine = sineWindow(4.4f, 0.3f)
        repeat(20) { step(sine) }
        // Held through the hold, whatever the noise looks like, then faded to the plain arc.
        var held = 0L
        while (held < ScopeHoldMillis.toLong()) {
            val noise = noiseWindow(0.3f, 100 + held.toInt())
            step(noise, triggered = false)
            held += 16
            assertTrue(correlation(sine, points()) > 0.999f, "noise leaked into the held shape at $held ms")
            assertTrue(abs(correlation(noise, points())) < 0.2f, "the trace followed the noise at $held ms")
        }
        assertTrue(peak() > 0.9f, "the held shape shrank inside the hold: ${peak()}")
        while (held < (ScopeHoldMillis + 3 * ScopeHoldFadeMillis).toLong()) {
            step(noiseWindow(0.3f, 100 + held.toInt()), triggered = false)
            held += 16
        }
        assertTrue(peak() < 0.1f, "a long run without a trigger kept its shape: ${peak()}")
    }

    @Test
    fun aHitWithNoTriggerYetSwellsTheHeldShape() {
        repeat(40) { step(sineWindow(4.4f, 0.05f)) }
        repeat(20) { step(sineWindow(4.4f, 0.01f)) }
        val quiet = peak()
        step(noiseWindow(0.5f, 7), triggered = false)
        assertTrue(peak() > quiet * 1.5f, "an untriggered hit did not swell the held shape: $quiet to ${peak()}")
        assertTrue(peak() <= 1f)
    }

    @Test
    fun loudnessNeverDrawsPastFullHeight() {
        repeat(120) { step(sineWindow(2.2f, 0.02f)) }
        // Hits far louder than the envelope, in shapes that differ from the quiet one.
        listOf(1f, 0.1f, 2f, 0.5f).forEachIndexed { i, amplitude ->
            repeat(8) { k ->
                step(if (k % 2 == 0) chordWindow(amplitude) else sineWindow(3f + i, amplitude))
                assertTrue(peak() <= 1f + 1e-6f, "a ${amplitude}x hit overdrew: ${peak()}")
            }
        }
    }

    @Test
    fun quietPassagesStillShowTheirShape() {
        repeat(30) { step(sineWindow(4.4f, 0.8f)) }
        // The tail after a hit reads quieter than the hit, for a while.
        repeat(6) { step(sineWindow(4.4f, 0.05f)) }
        assertTrue(peak() < 0.5f, "the tail drew as tall as the hit: ${peak()}")
        // Then the gain comes up to meet a quiet passage.
        repeat(250) { step(sineWindow(4.4f, 0.05f)) }
        assertTrue(peak() > 0.9f, "a quiet passage never grew to full height: ${peak()}")
        // Far below the envelope's floor it still shows, but never at full height.
        repeat(250) { step(sineWindow(4.4f, 0.001f)) }
        assertEquals(sqrt(0.001f / ScopeEnvelopeFloor), peak(), 0.02f, "a -60 dBFS tone's height")
    }

    @Test
    fun eachFrameKeepsAboutAThirdOfTheLast() {
        val a = sineWindow(2f, 0.3f)
        val b = sineWindow(4f, 0.3f)
        repeat(30) { step(a) }
        step(b)
        for (i in a.indices) assertEquals(ScopeSmoothing * a[i] / 0.3f + (1 - ScopeSmoothing) * b[i] / 0.3f, trace[i], 1e-4f, "point $i")
    }

    @Test
    fun theBlendDoesNotDependOnThePollRate() {
        val a = sineWindow(2f, 0.3f)
        val b = sineWindow(4f, 0.3f)
        repeat(30) { step(a) }
        step(b, dtMs = 16)
        val once = points()

        val other = ScopeTrace()
        val otherFrame = TestScopeFrame()
        var t = 1_000L
        repeat(60) { t += 8; otherFrame.publish(a, true); other.update(otherFrame, t) }
        repeat(2) { t += 8; otherFrame.publish(b, true); other.update(otherFrame, t) }
        for (i in once.indices) assertEquals(once[i], other[i], 1e-4f, "two 8ms polls differ from one 16ms poll at $i")
    }

    /**
     * How far from the centre each point lies of the path the ring draws for [trace] at full
     * progress, in a box [size] px across at density 3: the drawing's own geometry, blur and taper.
     */
    private fun drawnTraceRadii(size: Float): List<Float> {
        val wave = ProgressWave(
            mutableFloatStateOf(0f), mutableFloatStateOf(1f), mutableFloatStateOf(0f), mutableLongStateOf(-1L),
            mutableLongStateOf(0L), FloatArray(8), clocked = false, scopeTrace = trace, scopeTickState = mutableIntStateOf(0),
        )
        val path = Path()
        val px = size.roundToInt()
        CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, Canvas(ImageBitmap(px, px)), Size(size, size)) {
            drawProgressRing(path, 1f, wave, Color.Cyan, RingStrokes(this.size), ZipGradient())
        }
        return buildList {
            for (segment in path) {
                val p = segment.points
                for (i in 0 until p.size / 2) add(hypot(p[2 * i] - size / 2, p[2 * i + 1] - size / 2))
            }
        }
    }

    // Both ring sizes, a square wave's corners and all: the line the ring draws never leaves the band
    // the beat wave had, from the ring box's edge in to the dome's clearance.
    @Test
    fun aFullHeightTraceStaysBetweenTheDomeAndTheRingBox() {
        repeat(20) { step(FloatArray(ScopePoints) { if ((it / 16) % 2 == 0) 1f else -1f }) }
        for (ring in listOf(56.dp, 64.dp, 144.dp)) {
            val size = ring.value * 3f
            val stroke = RingStrokes(Size(size, size)).scopeWidth
            val radii = drawnTraceRadii(size)
            assertTrue(radii.size > 100, "sanity: the $ring ring drew no trace")
            val outer = radii.max() + stroke / 2
            val inner = radii.min() - stroke / 2
            assertTrue(outer <= size / 2 + 1e-3f, "a $ring trace left the ring box: $outer > ${size / 2}")
            val clearance = size / 2 - ringInnerClearance(ring).value * 3f
            assertTrue(inner >= clearance - 1e-3f, "a $ring trace reached into the dome's clearance: $inner < $clearance")
            assertTrue(outer > size / 2 - 1f && inner < clearance + 1f, "a $ring trace never used its band: $inner..$outer")
        }
    }

    @Test
    fun aShortArcIsThePlainArc() {
        assertEquals(0f, scopeArcFade(0.1f))
        assertEquals(1f, scopeArcFade(0.6f))
        assertTrue(scopeArcFade(0.3f) in 0.1f..0.9f)
    }

    // ==================== the rings and the feed ====================

    @Test
    fun theRingsHoldTheFeedOnlyWhilePlayingOffTelevision() {
        val calls = mutableListOf<Boolean>()
        val feed = ScopeFeed(TestScopeFrame()) { calls += it }
        var barPaused by mutableStateOf(false)
        var domePaused by mutableStateOf(false)
        var domeShown by mutableStateOf(true)
        val scene = ImageComposeScene(300, 200, Density(1f)) {
            CompositionLocalProvider(LocalScopeFeed provides feed) {
                Row {
                    VibeTransportRing(paused = barPaused, progress = 0.62f)
                    if (domeShown) VibeTransportRing(paused = domePaused, progress = 0.62f, ringSize = 144.dp)
                }
            }
        }
        fun frame() = Snapshot.sendApplyNotifications().also { scene.render() }
        try {
            frame()
            assertEquals(listOf(true), calls, "two playing rings enable the scope once")
            barPaused = true
            frame()
            assertEquals(listOf(true), calls, "the dome still plays")
            domePaused = true
            frame()
            assertEquals(listOf(true, false), calls, "no ring plays, so the scope stops")
            barPaused = false
            frame()
            domeShown = false
            frame()
            assertEquals(listOf(true, false, true), calls, "a paused ring leaving holds nothing")
            barPaused = true
            frame()
            assertEquals(listOf(true, false, true, false), calls)
        } finally {
            scene.close()
        }

        val tvCalls = mutableListOf<Boolean>()
        val tvFeed = ScopeFeed(TestScopeFrame()) { tvCalls += it }
        listOf(true to 0.62f, false to null).forEach { (tv, progress) ->
            val other = ImageComposeScene(200, 200, Density(1f)) {
                CompositionLocalProvider(LocalScopeFeed provides tvFeed, LocalTelevisionHardware provides tv) {
                    VibeTransportRing(paused = false, progress = progress)
                }
            }
            try {
                other.render()
            } finally {
                other.close()
            }
        }
        assertEquals(emptyList(), tvCalls, "a TV ring, or one with no progress, polled the scope")
    }

    // Rings torn down mid-play, as a layout change or the app closing does: each hold goes with its
    // ring, and the scope stops with the last one.
    @Test
    fun aPlayingRingUnmountedReleasesItsHold() {
        val calls = mutableListOf<Boolean>()
        val feed = ScopeFeed(TestScopeFrame()) { calls += it }
        var barShown by mutableStateOf(true)
        var domeShown by mutableStateOf(true)
        val scene = ImageComposeScene(300, 200, Density(1f)) {
            CompositionLocalProvider(LocalScopeFeed provides feed) {
                Row {
                    if (barShown) VibeTransportRing(paused = false, progress = 0.62f)
                    if (domeShown) VibeTransportRing(paused = false, progress = 0.62f, ringSize = 144.dp)
                }
            }
        }
        fun frame() = Snapshot.sendApplyNotifications().also { scene.render() }
        try {
            frame()
            assertEquals(listOf(true), calls, "two playing rings enable the scope once")
            domeShown = false
            frame()
            assertEquals(listOf(true), calls, "the bar's hold went with the dome's")
            barShown = false
            frame()
            assertEquals(listOf(true, false), calls, "the last playing ring left and the scope kept polling")
            // The whole scene disposed under a playing ring.
            barShown = true
            frame()
            assertEquals(listOf(true, false, true), calls)
        } finally {
            scene.close()
        }
        assertEquals(listOf(true, false, true, false), calls, "a ring disposed with its scene kept the scope polling")
    }

    private fun ringScene(scope: TestScopeFrame, paused: () -> Boolean = { false }) =
        ImageComposeScene(128, 128, Density(2f)) {
            CompositionLocalProvider(LocalScopeFeed provides ScopeFeed(scope) {}) {
                VibeTransportRing(paused = paused(), progress = 0.62f)
            }
        }

    private fun ImageComposeScene.frame(ms: Long): ByteArray = render(ms * 1_000_000).encodeToData()!!.bytes

    @Test
    fun aPlayingRingTracesEachWindowAndHoldsTheLastOnPause() {
        var paused by mutableStateOf(false)
        val scope = TestScopeFrame()
        val scene = ringScene(scope) { paused }
        val windows = listOf(sineWindow(3f, 0.3f), chordWindow(0.3f), sineWindow(5.5f, 0.2f))
        try {
            (0 until 30).forEach { scope.publish(windows[it / 10], true); scene.frame(1_000L + it * 16) }
            val playing = scene.frame(1_480)
            scope.publish(windows[0], true)
            assertFalse(playing.contentEquals(scene.frame(1_496)), "a new window never reached the ring")
            paused = true
            Snapshot.sendApplyNotifications()
            val held = scene.frame(1_512)
            // New windows keep coming (a poll winding down); the paused ring holds its own.
            scope.publish(windows[1], true)
            assertTrue(held.contentEquals(scene.frame(1_528)), "the paused ring took a new window")
        } finally {
            scene.close()
        }
    }

    // Noise with no trigger, from the start, draws exactly what silence does: the plain arc.
    @Test
    fun untriggeredNoiseDrawsWhatSilenceDraws() {
        val noisy = TestScopeFrame()
        val quiet = TestScopeFrame()
        val noiseScene = ringScene(noisy)
        val silentScene = ringScene(quiet)
        val silence = FloatArray(ScopePoints)
        try {
            var last = ByteArray(0) to ByteArray(0)
            repeat(40) {
                noisy.publish(noiseWindow(0.5f, it), false)
                quiet.publish(silence, false)
                last = noiseScene.frame(1_000L + it * 16) to silentScene.frame(1_000L + it * 16)
            }
            assertTrue(last.first.contentEquals(last.second), "untriggered noise drew something")
        } finally {
            noiseScene.close()
            silentScene.close()
        }
    }
}
