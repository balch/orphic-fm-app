package org.balch.orpheus.djapp.bench

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.bench.OverviewCache
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MixBenchServerTest {
    private val vibe = Vibe(
        name = "bench",
        tracks = List(8) { TrackVoice(OrpheusEngine(OrpheusEngineId.VA), OrpheusEngine(OrpheusEngineId.VA)) },
        bpm = 120f,
        rootNote = RootNote.A,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f,
            ghostProbability = 0f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
    )
    private val feature = FakeFeature(vibe)
    private val ports = ConcurrentHashMap<String, PortValue>()
    private val synth = SynthController().apply {
        setDelegates(setter = { id, v -> ports[id.symbol] = v; true }, getter = { ports[it.symbol] })
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = MixBenchController(feature, synth, NoTap, scope)
    private val root: File = Files.createTempDirectory("mixbench").toFile()
    private val server = MixBenchServer(
        controller = controller,
        pulsar = feature,
        transport = NoTransport,
        captures = CaptureWriter(File(root, "build/mix-bench/captures"), root),
        overview = OverviewCache(root, launch = { 1 }),
        vibesDir = null,
    )
    private val url = server.start()

    @AfterTest
    fun tearDown() {
        server.stop()
        runBlocking { controller.stop() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun base() = url.substringBefore("?")
    private fun key() = url.substringAfter("key=")

    @Test
    fun rejectsWithoutKey() {
        val c = URI(base()).toURL().openConnection() as HttpURLConnection
        assertEquals(403, c.responseCode)
        val wrong = URI(base() + "?key=nope").toURL().openConnection() as HttpURLConnection
        assertEquals(403, wrong.responseCode)
        val ok = URI(url).toURL().openConnection() as HttpURLConnection
        assertEquals(200, ok.responseCode)
    }

    @Test
    fun keyIs32RandomBytesAndOneCharOffFails() {
        assertTrue(key().matches(Regex("[0-9a-f]{64}")), "key ${key()}")
        val last = key().last()
        val near = key().dropLast(1) + (if (last == '0') '1' else '0')
        val c = URI(base() + "?key=" + near).toURL().openConnection() as HttpURLConnection
        assertEquals(403, c.responseCode)
        val prefix = URI(base() + "?key=" + key().dropLast(1)).toURL().openConnection() as HttpURLConnection
        assertEquals(403, prefix.responseCode)
    }

    @Test
    fun secondClientGetsSnapshot() {
        val a = openEvents()
        val b = openEvents()
        try {
            assertEquals("snapshot", firstEvent(a))
            assertEquals("snapshot", firstEvent(b))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun cmdLevelReachesController() {
        val c = URI(base() + "cmd?key=" + key()).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"op":"level","t":2,"db":-6}""".toByteArray()) }
        assertEquals(200, c.responseCode)
        assertTrue(c.inputStream.bufferedReader().readText().contains("\"ok\":true"))
        assertEquals(-6f, controller.layers.value.vibe.levelDb[2])
    }

    @Test
    fun captureWritesToTheRepoWithoutAService() {
        val reply = post(url, """{"op":"capture"}""")
        assertTrue(reply.contains("\"ok\":true"), reply)
        assertEquals(1, File(root, "build/mix-bench/captures").listFiles()?.size, reply)
    }

    @Test
    fun captureGoesThroughTheServiceWhenGiven() {
        val out = File(root, "app-captures/x.json")
        var calls = 0
        val service = object : MixCaptureService {
            override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>): CaptureOutcome {
                calls++
                return CaptureOutcome(true, "applied", out.path, emptyList(), null, aiVibe = true)
            }
        }
        val s = MixBenchServer(controller, feature, NoTransport, CaptureWriter(root, root), OverviewCache(root, launch = { 1 }), null, captureService = service)
        val u = s.start()
        try {
            val reply = post(u, """{"op":"capture"}""")
            assertEquals(1, calls)
            assertTrue(reply.contains("\"aiVibe\":true"), reply)
            assertTrue(reply.contains(out.path), reply)
            assertTrue(File(root, "build/mix-bench/captures").listFiles().isNullOrEmpty())
        } finally {
            s.stop()
        }
    }

    @Test
    fun staleBannerReadsTheServiceBaseline() {
        val vibes = File(root, "vibes").apply { mkdirs() }
        File(vibes, "VibeNames.kt").writeText("val BENCH = VibeName(\"bench\")\n")
        File(vibes, "BenchVibe.kt").writeText("val v = Vibe(name = VibeNames.BENCH)\n")
        // The service recorded a different hash at load, so the source changed since: the page must say so too.
        val service = object : MixCaptureService {
            override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>) = error("unused")
            override fun baselineHash(vibeName: String) = if (vibeName == "bench") "deadbeef" else null
        }
        val s = MixBenchServer(controller, feature, NoTransport, CaptureWriter(root, root), OverviewCache(root, launch = { 1 }), vibes, captureService = service)
        val u = s.start()
        val events = LinkedBlockingQueue<Pair<String, String>>()
        val conn = URI(u.substringBefore("?") + "events?key=" + u.substringAfter("key=")).toURL().openConnection() as HttpURLConnection
        conn.readTimeout = 10_000
        val reader = thread(isDaemon = true) { readEvents(BufferedReader(InputStreamReader(conn.inputStream)), events) }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                val (name, data) = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if ((name == "state" || name == "snapshot") && "\"stale\":true" in data) return
            }
            fail("the page never showed stale")
        } finally {
            s.stop()
            conn.disconnect()
            reader.interrupt()
        }
    }

    private fun post(pageUrl: String, body: String): String {
        val c = URI(pageUrl.substringBefore("?") + "cmd?key=" + pageUrl.substringAfter("key=")).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    @Test
    fun bindsLoopbackOnly() {
        assertTrue(server.address.address.isLoopbackAddress)
        assertTrue(url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"), url)
    }

    @Test
    fun repickResendsOverview() {
        val alpha = vibe.copy(name = "Alpha")
        val beta = vibe.copy(name = "Beta")
        val vibes = File(root, "vibes").apply { mkdirs() }
        File(vibes, "VibeNames.kt").writeText("val ALPHA = VibeName(\"Alpha\")\nval BETA = VibeName(\"Beta\")\n")
        File(vibes, "AlphaVibe.kt").writeText("val v = Vibe(name = VibeNames.ALPHA)\n")
        File(vibes, "BetaVibe.kt").writeText("val v = Vibe(name = VibeNames.BETA)\n")
        // Stands in for measure.sh: writes the untagged overview the cache renames.
        val cache = OverviewCache(root, launch = { cmd ->
            val slug = cmd.last().lowercase()
            File(root, "build/mix-bench/overview").mkdirs()
            File(root, "build/mix-bench/overview/$slug.overview.json")
                .writeText("""{"seconds":10,"sections":[],"env":[[-0.1,0.1]]}""")
            0
        })
        val f = FakeFeature(alpha, listOf(alpha, beta))
        val c = MixBenchController(f, synth, NoTap, scope)
        val s = MixBenchServer(c, f, NoTransport, CaptureWriter(File(root, "build/mix-bench/captures"), root), cache, vibes)
        val u = s.start()
        val events = LinkedBlockingQueue<Pair<String, String>>()
        val conn = URI(u.substringBefore("?") + "events?key=" + u.substringAfter("key=")).toURL().openConnection() as HttpURLConnection
        conn.readTimeout = 10_000
        val reader = thread(isDaemon = true) { readEvents(BufferedReader(InputStreamReader(conn.inputStream)), events) }
        try {
            awaitOverview(events, "Alpha")
            f.applyVibe(beta)
            awaitOverview(events, "Beta")
            f.applyVibe(alpha)
            awaitOverview(events, "Alpha")
        } finally {
            s.stop()
            conn.disconnect()
            reader.interrupt()
        }
    }

    private fun readEvents(r: BufferedReader, out: LinkedBlockingQueue<Pair<String, String>>) {
        var name = ""
        try {
            while (true) {
                val line = r.readLine() ?: return
                if (line.startsWith("event:")) name = line.removePrefix("event:").trim()
                else if (line.startsWith("data:")) out.add(name to line.removePrefix("data:").trim())
            }
        } catch (_: Exception) {
        }
    }

    private fun awaitOverview(events: LinkedBlockingQueue<Pair<String, String>>, vibe: String) {
        // Meters keep arriving, so wait against a deadline rather than for a quiet queue.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val (name, data) = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (name == "overview" && data.startsWith("{\"vibe\":\"$vibe\"")) return
        }
        fail("no overview event for $vibe")
    }

    private fun openEvents(): BufferedReader {
        val c = URI(base() + "events?key=" + key()).toURL().openConnection() as HttpURLConnection
        c.readTimeout = 5_000
        return BufferedReader(InputStreamReader(c.inputStream))
    }

    private fun firstEvent(r: BufferedReader): String {
        while (true) {
            val line = r.readLine() ?: error("stream closed before an event")
            if (line.startsWith("event:")) return line.removePrefix("event:").trim()
        }
    }

    private object NoTap : MixBenchTap {
        override fun setEnabled(on: Boolean) {}
        override fun drain(out: FloatArray): Int = 0
    }

    private object NoTransport : BenchTransport {
        override val playing: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play() {}
        override fun pause() {}
    }

    private class FakeFeature(initial: Vibe, override val vibeList: List<Vibe> = listOf(initial)) : PulsarFeature {
        override val vibeNames = vibeList.map { it.name }
        override val vibeFlow = MutableStateFlow(initial)
        override val vibeLoadCount = MutableStateFlow(0)
        override fun applyVibe(vibe: Vibe) {
            vibeFlow.value = vibe
            vibeLoadCount.value++
        }
        override val arrangementStateFlow: StateFlow<PulsarArrangementState> = MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
        override val stateFlow = MutableStateFlow(PulsarUiState(vibe = initial))
        override val actions = PulsarPanelActions()
    }
}
