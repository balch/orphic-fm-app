package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.core.plugin.viz.VizChannel
import org.balch.orpheus.core.plugin.viz.VizHistory
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.VizStage
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlin.test.Test

/**
 * Renders the Orphoscope with a synthetic groove on all eight Pulsar tracks plus the DJ graph's
 * live buses, and prints what a frame costs (raster wall time and JVM bytes). Asserts nothing.
 * Output: features/visualizations/build/orphoscope-render/, numbers in
 * build/test-results/jvmTest/TEST-*OrphoscopeRenderHarness.xml.
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*OrphoscopeRenderHarness*' -PrenderHarness --rerun
 */
class OrphoscopeRenderHarness {
    private val blocksPerBeat = 47 // 120 bpm at ~94 blocks a second

    private fun hit(k: Int, every: Int, offset: Int, level: Float, decay: Float): Float {
        val since = Math.floorMod(k - offset, every)
        return level * exp(-since / decay)
    }

    /** A history ring ending at block [end], laid out as [VizHistory] keeps one: block k at its slot. */
    private fun ring(end: Int, sample: (Int) -> Float): FloatArray {
        val mask = VizHistory.RING_SIZE - 1
        return FloatArray(VizHistory.RING_SIZE) { slot -> sample(end - 1 - ((end - 1 - slot) and mask)) }
    }

    /** One per-block peak history per track, as the engine's VIZ_PULSAR_TRACK rings carry. */
    private fun groove(end: Int): Array<FloatArray> = Array(8) { t ->
        ring(end) { k ->
            when (t) {
                0 -> hit(k, blocksPerBeat, 0, 0.7f, 6f)
                1 -> hit(k, blocksPerBeat * 2, blocksPerBeat + blocksPerBeat / 2, 0.3f, 4f)
                2 -> hit(k, blocksPerBeat / 2, blocksPerBeat / 4, 0.06f, 2f)
                3 -> 0.35f * (1f - 0.3f * Math.floorMod(k, blocksPerBeat / 2) / (blocksPerBeat / 2f))
                4 -> hit(k, blocksPerBeat * 2, 0, 0.2f, 40f)
                5 -> 0.12f + 0.06f * sin(2 * PI * k / 400).toFloat()
                6 -> 0.02f + 0.02f * ((k * 7919) % 13) / 13f
                else -> if (Math.floorMod(k, 752) < 188) 0.15f * Math.floorMod(k, 752) / 188f else 0f
            }
        }
    }

    private fun bus(end: Int, level: Float, offset: Int = 0): FloatArray =
        ring(end) { k -> hit(k + offset, blocksPerBeat, 0, level, 12f) + level * 0.3f }

    private class Feed {
        val tracks = MutableStateFlow<VizHistory?>(null)
        val buses = MutableStateFlow<VizHistory?>(null)
        val grid = MutableStateFlow(PulsarVizData())
    }

    // The DJ graph's live buses, by viz channel id.
    private val busIds = intArrayOf(VizChannel.MASTER_OUT, VizChannel.DELAY_IN, VizChannel.DELAY_OUT, VizChannel.REVERB_IN, VizChannel.REVERB_OUT)

    /** Only the viz flow getters are answered: the Orphoscope reads nothing else. */
    private fun engine(feed: Feed): SynthEngine {
        return Proxy.newProxyInstance(SynthEngine::class.java.classLoader, arrayOf(SynthEngine::class.java)) { proxy, method, _ ->
            when {
                method.name == "getPulsarTrackHistory" -> feed.tracks
                method.name == "getScopeHistory" -> feed.buses
                method.name == "getPulsarVizFlow" -> feed.grid
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> false
                method.name == "toString" -> "OrphoscopeHarnessEngine"
                else -> error("unexpected ${method.name}")
            }
        } as SynthEngine
    }

    private class Started : LifecycleOwner {
        override val lifecycle: Lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private class Frame(val end: Int, val tracks: Array<FloatArray>, val buses: List<FloatArray>, val grid: PulsarVizData)

    // The same groove as steps: kick on the beat, perc on 2 and 4, hats and bass on 8ths, keys per bar.
    private val gates = Array(8) { t ->
        BooleanArray(32) { s ->
            when (t) {
                0 -> s % 4 == 0
                1 -> s % 8 == 6
                2, 3 -> s % 2 == 0
                4 -> s % 16 == 0
                5 -> s == 0
                else -> false
            }
        }
    }
    private val velocities = Array(8) { t -> FloatArray(32) { s -> if (s % 4 == 0) 1f else 0.6f - t * 0.03f } }

    private fun grid(shift: Int) = PulsarVizData(
        stepGates = gates,
        stepVelocities = velocities,
        playheads = IntArray(8) { (shift * 4 / blocksPerBeat) % 16 },
    )

    // A ring at ~94 blocks a second polled each 60 Hz frame: one or two new samples a frame. Each
    // ring's newest sample is block `end`, so its content lines up with the scroll's clock.
    private fun frames(withPulsar: Boolean) = Array(64) { f ->
        val end = 1000 + f * 94 / 60
        val shift = end - 480
        Frame(
            end,
            if (withPulsar) groove(end) else Array(8) { FloatArray(VizHistory.RING_SIZE) },
            listOf(bus(end, 0.8f), bus(end, 0.3f), bus(end, 0.2f, 20), bus(end, 0.25f), bus(end, 0.15f, 30)),
            if (withPulsar) grid(shift) else PulsarVizData(),
        )
    }

    private fun publish(feed: Feed, frame: Frame, withPulsar: Boolean) {
        val now = TimeSource.Monotonic.markNow()
        val ends = IntArray(VizChannel.COUNT) { frame.end }
        if (withPulsar) {
            val samples = Array(VizChannel.COUNT) { ch ->
                val t = ch - VizChannel.PULSAR_TRACK_0
                if (t in 0 until 8) frame.tracks[t] else FloatArray(0)
            }
            feed.tracks.value = VizHistory(samples, ends, now)
        }
        val busSamples = Array(VizChannel.COUNT) { ch ->
            val i = busIds.indexOf(ch)
            if (i >= 0) frame.buses[i] else FloatArray(0)
        }
        feed.buses.value = VizHistory(busSamples, ends, now)
        feed.grid.value = frame.grid
        Snapshot.sendApplyNotifications()
    }

    // repeatOnLifecycle registers from the main dispatcher, which this module's tests lack: without
    // one no collector ever starts, and a version that redraws from collected state draws nothing.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun run(name: String, width: Int, height: Int, density: Float, withPulsar: Boolean, stage: Rect? = null) {
        @OptIn(ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val feed = Feed()
        val viz = SignalMonitorViz(engine(feed))
        val vizStage = stage?.let { VizStage().apply { report(it) } }
        val scene = ImageComposeScene(width, height, Density(density)) {
            CompositionLocalProvider(LocalVizStage provides vizStage, LocalLifecycleOwner provides Started()) {
                viz.Content(Modifier.fillMaxSize())
            }
        }
        val all = frames(withPulsar)
        try {
            val dir = File("build/orphoscope-render").apply { mkdirs() }
            var t = 1_000_000_000L
            repeat(60) { publish(feed, all[it % all.size], withPulsar); scene.render(t).close(); t += 16_666_667L }
            File(dir, "$name.png").writeBytes(scene.render(t).encodeToData()!!.bytes)

            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val id = Thread.currentThread().id
            System.gc()
            val bytesBefore = threads.getThreadAllocatedBytes(id)
            val start = System.nanoTime()
            val count = 240
            repeat(count) { publish(feed, all[it % all.size], withPulsar); scene.render(t).close(); t += 16_666_667L }
            val ms = (System.nanoTime() - start) / 1e6 / count
            val bytes = (threads.getThreadAllocatedBytes(id) - bytesBefore) / count
            println("[orphoscope] $name ${width}x$height: ${"%.2f".format(ms)} ms/frame, $bytes B/frame")
        } finally {
            scene.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun desktopWithPulsar() = run("desktop_pulsar", 1600, 1000, 1f, withPulsar = true)
    @Test fun fullscreenDesktopWithPulsar() = run("fullscreen_pulsar", 2560, 1440, 1f, withPulsar = true)
    @Test fun retinaFullscreenWithPulsar() = run("retina_fullscreen_pulsar", 3024, 1964, 2f, withPulsar = true)
    @Test fun desktopBusesOnly() = run("desktop_buses_only", 1600, 1000, 1f, withPulsar = false)
    @Test fun phoneStageWithPulsar() =
        run("phone_stage_pulsar", 780, 1688, 2f, withPulsar = true, stage = Rect(0f, 220f, 780f, 1380f))
    @Test fun phoneLandscapeStageWithPulsar() =
        run("phone_landscape_stage_pulsar", 1688, 780, 2f, withPulsar = true, stage = Rect(0f, 100f, 1688f, 680f))
}
