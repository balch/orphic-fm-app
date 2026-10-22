package org.balch.orpheus.djapp.ai.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.mcp.MixCaptureFlow
import org.balch.orpheus.djapp.ai.mcp.repoRoot
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.tools.decodeVibe
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.CaptureFile
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.withCapture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records each archive write; [onArchive] runs inside it, standing in for whatever happens during the IO. */
private class FakeArchive(private val result: Boolean = true, private val onArchive: () -> Unit = {}) : AiVibeArchive {
    val writes = mutableListOf<Pair<String, String>>()
    override suspend fun archive(vibeName: String, vibeJson: String): Boolean {
        writes += vibeName to vibeJson
        onArchive()
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureToolTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val catalog = vibe("Night Drive")
    // An AI vibe: playing, but not in the catalog.
    private val aiVibe = vibe("Velvet Static")
    private val pulsar = FakePulsar(listOf(catalog))
    private val dir: File = Files.createTempDirectory("mix-captures").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun TestScope.controller() = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.flow(c: MixBenchController, archive: AiVibeArchive, repo: File? = null, follow: Boolean = false) =
        MixCaptureFlow(
            { pulsar }, { c }, archive, OneDispatcher(StandardTestDispatcher(testScheduler)), CaptureWriter(dir, repo),
            scope = if (follow) backgroundScope else null,
        )

    private fun TestScope.tool(c: MixBenchController, archive: AiVibeArchive) = MixCaptureTool(flow(c, archive)).tool

    /** A checkout holding just the Night Drive source. */
    private fun fakeRepo(): Pair<File, File> {
        val repo = Files.createTempDirectory("repo").toFile()
        val vibes = File(repo, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes").apply { mkdirs() }
        File(vibes, "VibeNames.kt").writeText("val NIGHT_DRIVE = VibeName(\"Night Drive\")\n")
        val src = File(vibes, "NightDriveVibe.kt").apply { writeText("val v = Vibe(name = VibeNames.NIGHT_DRIVE)\n") }
        return repo to src
    }

    /** Pan and level on the whole vibe, and an energy move. */
    private suspend fun MixBenchController.mix() {
        setPan(2, -0.5f)
        setLevelDb(4, -6f)
        assertTrue(setMacro(Macro.ENERGY, 0.7f))
    }

    @Test
    fun aiVibeCaptureArchivesAndReapplies() = runTest {
        pulsar.applyVibe(aiVibe)
        val c = controller()
        c.mix()
        val expectedRows = capture(aiVibe, c.layers.value)
        val expected = aiVibe.withCapture(expectedRows)
        val archive = FakeArchive()
        val loads = pulsar.vibeLoadCount.value

        val r = tool(c, archive).execute(NoArgs())
        runCurrent()

        assertTrue(r.ok, r.message)
        assertTrue(r.aiVibe)
        assertTrue(r.appliedToAiVibe)
        assertTrue("is archived" in r.message, r.message)
        assertTrue("song restarts" in r.message, r.message)
        assertNull(r.stale)
        assertEquals(expectedRows, r.rows)
        assertEquals(4, r.rows.size, "${r.rows}")
        val (name, json) = archive.writes.single()
        assertEquals("Velvet Static", name)
        assertEquals(expected, decodeVibe(vibeApplyJson, json).getOrThrow())
        assertEquals(expected, pulsar.vibeFlow.value)
        assertEquals(loads + 1, pulsar.vibeLoadCount.value)
        assertEquals(-0.5f, pulsar.vibeFlow.value.tracks[2].pan)
        assertEquals(0.7f, pulsar.vibeFlow.value.energy)
        // The load follower cleared the layers: the vibe now holds them.
        assertEquals(BenchLayers(), c.layers.value)
        val file = File(assertNotNull(r.file))
        assertEquals(dir, file.parentFile)
        val written = Json.decodeFromString(CaptureFile.serializer(), file.readText())
        assertTrue(written.aiVibe)
        assertEquals(aiVibe, decodeVibe(vibeApplyJson, written.vibeJson).getOrThrow())
    }

    @Test
    fun sourceEditBetweenLoadAndCaptureIsStale() = runTest {
        val (repo, src) = fakeRepo()
        val c = controller()
        val f = flow(c, FakeArchive(), repo, follow = true)
        runCurrent()
        val atLoad = assertNotNull(f.baselineHash("Night Drive"))
        // Claude applies an earlier capture to the source; the app still plays the build it loaded.
        src.appendText("// edited\n")
        pulsar.applyVibeByName("Night Drive")
        runCurrent()
        assertEquals(atLoad, f.baselineHash("Night Drive"))
        c.mix()

        val r = MixCaptureTool(f).tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertEquals(true, r.stale)
        val written = Json.decodeFromString(CaptureFile.serializer(), File(assertNotNull(r.file)).readText())
        assertEquals(atLoad, written.baselineHash)
        assertEquals(true, written.stale)
    }

    @Test
    fun unrecordedLoadIsUnknownNotFresh() = runTest {
        val (repo, _) = fakeRepo()
        val c = controller()
        c.mix()
        val r = MixCaptureTool(flow(c, FakeArchive(), repo)).tool.execute(NoArgs())
        assertTrue(r.ok, r.message)
        assertNull(r.stale)
    }

    @Test
    fun clampedRowsAreNamedAndTheVibeGetsTheCap() = runTest {
        pulsar.applyVibe(aiVibe)
        val c = controller()
        c.setLevelDb(4, 3f)

        val r = tool(c, FakeArchive(result = false)).execute(NoArgs())
        runCurrent()

        assertTrue(r.ok, r.message)
        assertTrue(r.appliedToAiVibe)
        assertEquals(1.06f, r.rows.first().clampedDb, 0.01f)
        assertTrue("track 4 VOLUME (whole vibe) by 1.1 dB" in r.message, r.message)
        assertTrue("track 4 VOLUME_SPACE (whole vibe) by 1.1 dB" in r.message, r.message)
        assertEquals(1f, pulsar.vibeFlow.value.tracks[4].engineEdm.volume)
        assertEquals(1f, pulsar.vibeFlow.value.tracks[4].engineSpace.volume)
        // The archive reported a failed write, so the message doesn't claim one.
        assertTrue("was not written" in r.message, r.message)
        assertFalse("is archived" in r.message, r.message)
    }

    @Test
    fun captureRefusesStaleAiVibe() = runTest {
        pulsar.applyVibe(aiVibe)
        val c = controller()
        c.mix()
        // The user loads a catalog vibe while the archive is being written.
        val archive = FakeArchive(onArchive = { pulsar.applyVibeByName("Night Drive") })

        val r = tool(c, archive).execute(NoArgs())

        assertFalse(r.ok, r.message)
        assertFalse(r.appliedToAiVibe)
        assertTrue(r.aiVibe)
        assertTrue("changed" in r.message, r.message)
        assertNotNull(r.file)
        assertEquals("Night Drive", pulsar.vibeFlow.value.name)
        assertEquals(2, pulsar.vibeLoadCount.value)
    }

    @Test
    fun catalogVibeCaptureWritesOnlyTheFile() = runTest {
        val c = controller()
        c.mix()
        val layers = c.layers.value
        val archive = FakeArchive()

        val r = tool(c, archive).execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertFalse(r.aiVibe)
        assertFalse(r.appliedToAiVibe)
        // No repo, so there is no source to compare.
        assertNull(r.stale)
        assertEquals(capture(catalog, layers), r.rows)
        assertTrue(archive.writes.isEmpty())
        assertEquals(0, pulsar.vibeLoadCount.value)
        assertEquals(layers, c.layers.value)
        val written = Json.decodeFromString(CaptureFile.serializer(), File(assertNotNull(r.file)).readText())
        assertEquals("Night Drive", written.vibe)
        assertNull(written.source)
    }

    @Test
    fun captureWaitsForAVibeLoadToBeFollowed() = runTest {
        val c = controller()
        c.mix()
        pulsar.applyVibe(aiVibe)

        val r = tool(c, FakeArchive()).execute(NoArgs())

        assertFalse(r.ok, r.message)
        assertNull(r.file)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun unavailableServiceSaysSo() = runTest {
        val tool = MixCaptureTool(UnavailableMixCaptureService()).tool
        val r = tool.execute(NoArgs())
        assertFalse(r.ok)
        assertEquals("capture not available on this platform yet", r.message)
        assertEquals("mix_capture", tool.descriptor.name)
    }

    @Test
    fun repoRootIsTheDspDirsParent() {
        val repo = File(Files.createTempDirectory("repo").toFile(), "checkout")
        File(repo, "features/pulsar").mkdirs()
        assertEquals(repo.absoluteFile, repoRoot(File(repo, "liborpheus_dsp").path))
        assertNull(repoRoot(null))
        assertNull(repoRoot(File(dir, "liborpheus_dsp").path))
    }
}
