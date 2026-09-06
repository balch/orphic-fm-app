package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sun.management.ThreadMXBean
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.pulsar.PulsarStepGrid
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.skia.Image
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Renders the step grid across several frame-clock ticks so its layers can be looked at instead
 * of guessed at, and measures what one frame of it allocates.
 * ImageComposeScene.render(nanoTime) drives the grid's withFrameNanos loop.
 * Writes to build/grid-render/ and asserts nothing, like DjLayoutRenderHarness.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*PulsarGridRenderHarness*' --rerun
 * The allocation test is most of the runtime. Its numbers land in
 * build/test-results/jvmTest/TEST-*.PulsarGridRenderHarness.xml, not on the console.
 */
class PulsarGridRenderHarness {

    @Test
    fun renderFrames() {
        val outDir = File("build/grid-render").apply { mkdirs() }
        listOf(856 to 290, 360 to 100).forEach { (w, h) ->
            runCatching {
                val g = GridScene(w, h)
                try {
                    fun frame(ms: Long, tag: String, playhead: Int, level: Float) {
                        g.render(ms, playhead, level).use { img ->
                            File(outDir, "grid-${w}x$h-$tag.png").writeBytes(img.encodeToData()!!.bytes)
                        }
                    }
                    frame(0, "0-idle", -1, 0f)
                    frame(16, "1-playing", 8, 0.9f)
                    frame(100, "2-t100ms", 8, 0.9f)
                    frame(250, "3-t250ms", 9, 0.9f)
                    frame(450, "4-t450ms", 10, 0.9f)
                } finally {
                    g.close()
                }
            }.onFailure { println("[grid-render] ${w}x$h skipped: $it"); it.printStackTrace() }
        }
        println("[grid-render] wrote PNGs to ${outDir.absolutePath}")
    }

    /**
     * Bytes allocated on the render thread per frame, across composition, layout and draw of
     * the grid, read from ThreadMXBean. PNG encoding is deliberately left out. Skia's native
     * allocations are invisible to this counter; it measures JVM garbage, which is what
     * triggers collections. The waveform layer draws nothing here (empty trackVizFlows), so
     * this is the cell grid and the glass.
     */
    @Test
    fun measureAllocationsPerFrame() {
        val mx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().id
        val frames = 240

        data class Case(val label: String, val moving: Boolean, val idle: Boolean)
        listOf(
            Case("idle: nothing playing, loop parked", moving = false, idle = true),
            Case("playing, playhead parked", moving = false, idle = false),
            Case("playing, playhead moving", moving = true, idle = false),
        ).forEach { c ->
            runCatching {
                val g = GridScene(856, 290)
                try {
                    var ms = 0L
                    var ph = if (c.idle) -1 else 8
                    var idx = 0
                    fun tick() {
                        ms += 16; idx++
                        // A step every 6 frames is ~150 BPM sixteenths.
                        if (c.moving && idx % 6 == 0) ph = (ph + 1) % 16
                        g.render(ms, ph, if (c.idle) 0f else 0.9f, pushData = !c.idle).close()
                    }
                    repeat(60) { tick() }   // warm-up: JIT, shader caches
                    System.gc(); Thread.sleep(50)
                    val before = mx.getThreadAllocatedBytes(tid)
                    repeat(frames) { tick() }
                    val perFrame = (mx.getThreadAllocatedBytes(tid) - before) / frames
                    println("[grid-alloc] %-40s %9d bytes/frame  = %5.1f MB/s at 60 fps"
                        .format(c.label, perFrame, perFrame * 60 / 1e6))
                } finally {
                    g.close()
                }
            }.onFailure { println("[grid-alloc] ${c.label} skipped: $it"); it.printStackTrace() }
        }
    }
}

/**
 * One composed grid whose frame clock and viz data the test drives by hand. Every render pushes
 * a newly allocated PulsarVizData, exactly what SynthEngineMonitor does
 * (`playheads = pulsarPlayheads.copyOf()`), under neverEqualPolicy so each push counts as a
 * change the way a real emission does.
 */
internal class GridScene(w: Int, h: Int) {
    private val gates = Array(8) { BooleanArray(32) }
    private val vel = Array(8) { FloatArray(32) }
    private val base = 1_000_000_000L   // a nanoTime of 0 would leave the loop's sentinel unset

    init {
        // Cosmic Techno pattern from PulsarStepGridPreview, plus step 8 lit on every track so
        // any track can be at a lit step when the playhead parks there.
        for (i in listOf(0, 4, 8, 12)) { gates[0][i] = true; vel[0][i] = 0.9f }
        for (i in listOf(2, 6, 10, 14)) { gates[1][i] = true; vel[1][i] = 0.6f }
        for (i in listOf(0, 2, 4, 6, 8, 10, 12, 14)) { gates[2][i] = true; vel[2][i] = 0.5f }
        for (i in listOf(0, 3, 6, 10, 13)) { gates[3][i] = true; vel[3][i] = 0.8f }
        for (i in listOf(0, 8)) { gates[4][i] = true; vel[4][i] = 0.5f }
        for (i in listOf(0, 8)) { gates[5][i] = true; vel[5][i] = 0.4f }
        for (i in listOf(1, 5, 9, 11, 15)) { gates[6][i] = true; vel[6][i] = 0.7f }
        for (i in listOf(3, 7, 11, 15)) { gates[7][i] = true; vel[7][i] = 0.6f }
        for (t in 0 until 8) { gates[t][8] = true; vel[t][8] = 0.9f }
    }

    private fun viz(playhead: Int, trackLevel: Float) = PulsarVizData(
        stepGates = gates,
        stepVelocities = vel,
        playheads = IntArray(8) { playhead },
        stepCounts = IntArray(8) { 16 },
        trackLevels = FloatArray(8) { trackLevel },
    )

    private val data = mutableStateOf(viz(-1, 0f), neverEqualPolicy())

    private val scene = ImageComposeScene(w, h, Density(1f)) {
        OrpheusTheme {
            Box(Modifier.size(w.dp, h.dp).background(Color(0xFF14141F))) {
                PulsarStepGrid(vizData = data, energy = 0.9f, modifier = Modifier.fillMaxSize())
            }
        }
    }

    fun render(ms: Long, playhead: Int, trackLevel: Float = 0f, pushData: Boolean = true): Image {
        if (pushData) {
            data.value = viz(playhead, trackLevel)
            Snapshot.sendApplyNotifications()
        }
        return scene.render(base + ms * 1_000_000L)
    }

    /**
     * True while anything in the scene still wants a frame: a pending recomposition, layout or
     * draw, or a coroutine suspended in withFrameNanos. The frame loop's footprint, in other
     * words; a grid with nothing to animate must leave this false.
     */
    fun hasInvalidations(): Boolean = scene.hasInvalidations()

    fun close() = scene.close()
}
