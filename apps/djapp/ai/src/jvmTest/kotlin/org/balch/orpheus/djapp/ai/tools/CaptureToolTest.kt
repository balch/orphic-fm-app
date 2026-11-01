package org.balch.orpheus.djapp.ai.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.djapp.ai.collab.VibeEditResult
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.HistoryChange
import org.balch.orpheus.djapp.ai.collab.HistoryPoint
import org.balch.orpheus.djapp.ai.collab.PointKind
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.mcp.MixCaptureFlow
import org.balch.orpheus.djapp.ai.mcp.repoRoot
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.CaptureFile
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.capture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun TestScope.flow(c: MixBenchController, repo: File? = null, follow: Boolean = false) =
        MixCaptureFlow(
            { pulsar }, { c }, OneDispatcher(StandardTestDispatcher(testScheduler)), CaptureWriter(dir, repo),
            scope = if (follow) backgroundScope else null,
        )

    /** The mixer a capture goes through, with [f] as its capture service. */
    private fun TestScope.mixer(c: MixBenchController, f: MixCaptureService = flow(c)) =
        CollabRig(pulsar, { c }, backgroundScope, captureService = { f }).mixer

    private fun TestScope.tool(c: MixBenchController, f: MixCaptureService = flow(c)) = MixCaptureTool(f, mixer(c, f)).tool

    /** A checkout holding just the Night Drive source. */
    private fun fakeRepo(): Pair<File, File> {
        val repo = Files.createTempDirectory("repo").toFile()
        val vibes = File(repo, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes").apply { mkdirs() }
        File(vibes, "VibeNames.kt").writeText("val NIGHT_DRIVE = VibeName(\"Night Drive\")\n")
        val src = File(vibes, "NightDriveVibe.kt").apply { writeText("val v = Vibe(name = VibeNames.NIGHT_DRIVE)\n") }
        return repo to src
    }

    /** The same moves straight on the controller, outside the History. */
    private suspend fun MixBenchController.mixDirect() {
        setPan(2, -0.5f)
        setLevelDb(4, -6f)
        assertTrue(setMacro(Macro.ENERGY, 0.7f))
    }

    /** Pan and level on the whole vibe, and an energy move, as History rows. */
    private suspend fun CollabMixer.mix(c: MixBenchController) {
        edit(EditOrigin.USER, MixControl.Pan(2)) { setPan(2, -0.5f) }
        edit(EditOrigin.USER, MixControl.Level(4)) { setLevelDb(4, -6f) }
        edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }
    }

    @Test
    fun aiVibeCaptureWritesFileAndKeepsRows() = runTest {
        pulsar.applyVibe(aiVibe)
        val c = controller()
        val m = mixer(c)
        m.mix(c)
        val layers = c.layers.value
        val loads = pulsar.vibeLoadCount.value

        val r = MixCaptureTool(flow(c), m).tool.execute(NoArgs())
        runCurrent()

        assertTrue(r.ok, r.message)
        assertTrue(r.aiVibe)
        assertNull(r.stale)
        assertEquals(capture(aiVibe, layers), r.rows)
        assertEquals(4, r.rows.size, "${r.rows}")
        // Nothing was applied: the vibe, its load count and the mix are as they were.
        assertEquals(aiVibe, pulsar.vibeFlow.value)
        assertEquals(loads, pulsar.vibeLoadCount.value)
        assertEquals(layers, c.layers.value)
        val file = File(assertNotNull(r.file))
        assertEquals(dir, file.parentFile)
        val written = Json.decodeFromString(CaptureFile.serializer(), file.readText())
        assertTrue(written.aiVibe)
        val h = m.history.value
        assertEquals(3, h.changes.size)
        val point = assertIs<HistoryPoint>(h.items.last())
        assertEquals(PointKind.CAPTURE, point.kind)
        assertEquals(r.file, point.captureFile)
        assertTrue(h.uncaptured.isEmpty())
    }

    @Test
    fun flowUsesFileVibeForVibeJson() = runTest {
        val c = controller()
        c.mixDirect()
        val file = catalog.copy(bpm = 90f)
        val change = VibeFieldChange("bpm", JsonPrimitive(90f), JsonPrimitive(96f))

        val r = flow(c).capture(file, listOf(change))

        assertTrue(r.ok, r.message)
        assertEquals(listOf(change), r.vibeRows)
        assertEquals(capture(file, c.layers.value), r.rows)
        assertTrue("1 vibe" in r.message, r.message)
        val written = Json.decodeFromString(CaptureFile.serializer(), File(assertNotNull(r.file)).readText())
        assertEquals(file, Json.decodeFromString(Vibe.serializer(), written.vibeJson))
        assertEquals(listOf(change), written.vibeRows)
    }

    @Test
    fun captureWithOnlyVibeRowsWritesFile() = runTest {
        val c = controller()
        val change = VibeFieldChange("bpm", JsonPrimitive(120f), JsonPrimitive(96f))

        val r = flow(c).capture(catalog, listOf(change))

        assertTrue(r.ok, r.message)
        assertTrue(r.rows.isEmpty())
        assertNotNull(r.file)
        assertEquals(listOf(change), r.vibeRows)
    }

    @Test
    fun captureToolReturnsVibeRows() = runTest {
        val c = controller()
        val m = mixer(c)
        assertIs<VibeEditResult.Applied>(m.editVibe(EditOrigin.USER, catalog.copy(scaleType = ScaleType.MAJOR)))
        runCurrent()

        val r = MixCaptureTool(flow(c), m).tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertEquals(listOf("scaleType"), r.vibeRows.map { it.path })
        assertTrue(r.rows.isEmpty())
        val written = Json.decodeFromString(CaptureFile.serializer(), File(assertNotNull(r.file)).readText())
        assertEquals(r.vibeRows, written.vibeRows)
        // The rows were read against the file, so vibeJson is the file's vibe and not the edited one playing.
        assertEquals(catalog, Json.decodeFromString(Vibe.serializer(), written.vibeJson))
        assertEquals(0, m.history.value.uncapturedCount)
    }

    @Test
    fun catalogCaptureAddsCaptureRow() = runTest {
        val c = controller()
        val m = mixer(c)
        m.mix(c)
        val layers = c.layers.value

        val r = MixCaptureTool(flow(c), m).tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertFalse(r.aiVibe)
        // No repo, so there is no source to compare.
        assertNull(r.stale)
        assertEquals(capture(catalog, layers), r.rows)
        assertEquals(0, pulsar.vibeLoadCount.value)
        assertEquals(layers, c.layers.value)
        val written = Json.decodeFromString(CaptureFile.serializer(), File(assertNotNull(r.file)).readText())
        assertEquals("Night Drive", written.vibe)
        assertNull(written.source)
        val point = assertIs<HistoryPoint>(m.history.value.items.last())
        assertEquals("Captured", point.label)
        assertEquals(PointKind.CAPTURE, point.kind)
        assertEquals(r.file, point.captureFile)
    }

    @Test
    fun secondCaptureWithNoNewChangesWritesNothing() = runTest {
        val c = controller()
        val m = mixer(c)
        m.mix(c)
        val tool = MixCaptureTool(flow(c), m).tool
        assertTrue(tool.execute(NoArgs()).ok)
        val items = m.history.value.items
        val files = dir.listFiles().orEmpty().size

        val r = tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertNull(r.file)
        assertEquals("No new mix or sound changes since the last capture; nothing written.", r.message)
        assertEquals(items, m.history.value.items)
        assertEquals(files, dir.listFiles().orEmpty().size)
    }

    @Test
    fun captureFileHoldsWholeChangeSetDiff() = runTest {
        val c = controller()
        val m = mixer(c)
        val tool = MixCaptureTool(flow(c), m).tool
        m.edit(EditOrigin.USER, MixControl.Pan(2)) { setPan(2, -0.5f) }
        val first = tool.execute(NoArgs())
        m.edit(EditOrigin.USER, MixControl.Level(4)) { setLevelDb(4, -6f) }

        val second = tool.execute(NoArgs())

        assertTrue(first.ok && second.ok, second.message)
        assertTrue(first.rows.isNotEmpty())
        assertEquals(first.rows.size + 2, second.rows.size, "${second.rows}")
        assertTrue(first.rows.all { it in second.rows })
        assertNotEquals(first.file, second.file)
    }

    @Test
    fun failedCaptureAddsNoPoint() = runTest {
        val c = controller()
        val refuse = object : MixCaptureService {
            override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>) =
                CaptureOutcome(false, "nope", null, emptyList(), null, false)
        }
        val m = mixer(c, refuse)
        m.mix(c)
        val items = m.history.value.items

        val r = MixCaptureTool(refuse, m).tool.execute(NoArgs())

        assertFalse(r.ok)
        assertEquals("nope", r.message)
        assertEquals(items, m.history.value.items)
    }

    @Test
    fun sourceEditBetweenLoadAndCaptureIsStale() = runTest {
        val (repo, src) = fakeRepo()
        val c = controller()
        val f = flow(c, repo, follow = true)
        val m = mixer(c, f)
        runCurrent()
        val atLoad = assertNotNull(f.baselineHash("Night Drive"))
        // Claude applies an earlier capture to the source; the app still plays the build it loaded.
        src.appendText("// edited\n")
        pulsar.applyVibeByName("Night Drive")
        runCurrent()
        assertEquals(atLoad, f.baselineHash("Night Drive"))
        m.mix(c)

        val r = MixCaptureTool(f, m).tool.execute(NoArgs())

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
        val f = flow(c, repo)
        val m = mixer(c, f)
        m.mix(c)
        val r = MixCaptureTool(f, m).tool.execute(NoArgs())
        assertTrue(r.ok, r.message)
        assertNull(r.stale)
    }

    @Test
    fun clampedRowsAreNamed() = runTest {
        pulsar.applyVibe(aiVibe)
        val c = controller()
        val m = mixer(c)
        m.edit(EditOrigin.USER, MixControl.Level(4)) { setLevelDb(4, 3f) }

        val r = MixCaptureTool(flow(c), m).tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertEquals(1.06f, r.rows.first().clampedDb, 0.01f)
        assertTrue("track 4 VOLUME (whole vibe) by 1.1 dB" in r.message, r.message)
        assertTrue("track 4 VOLUME_SPACE (whole vibe) by 1.1 dB" in r.message, r.message)
    }

    @Test
    fun captureDuringHearWritesTheHistoryNotThePreview() = runTest {
        val c = controller()
        val rig = CollabRig(pulsar, { c }, backgroundScope, captureService = { flow(c) })
        runCurrent()
        rig.mixer.markPoint()
        rig.mixer.edit(EditOrigin.USER, MixControl.Level(4)) { setLevelDb(4, -6f) }
        val history = rig.mixer.history.value
        assertTrue(rig.mixer.hear(history.items.first().id))
        assertEquals(BenchLayers(), c.layers.value)

        val r = MixCaptureTool(flow(c), rig.mixer).tool.execute(NoArgs())

        assertTrue(r.ok, r.message)
        assertNull(rig.mixer.hearing.value)
        assertEquals(capture(catalog, history.layers()), r.rows)
        assertTrue(r.rows.isNotEmpty())
        assertEquals(history.layers(), c.layers.value)
        assertEquals("Captured", assertIs<HistoryPoint>(rig.mixer.history.value.items.last()).label)
    }

    @Test
    fun serviceWaitsForAVibeLoadToBeFollowed() = runTest {
        val c = controller()
        c.mixDirect()
        pulsar.applyVibe(aiVibe)

        val r = flow(c).capture()

        assertFalse(r.ok, r.message)
        assertNull(r.file)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun unavailableServiceSaysSo() = runTest {
        val c = controller()
        val service = UnavailableMixCaptureService()
        val m = mixer(c, service)
        m.mix(c)
        val tool = MixCaptureTool(service, m).tool
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
