package org.balch.orpheus.djapp.ai.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.djapp.ai.tools.FakeStemTap
import org.balch.orpheus.djapp.ai.tools.OneDispatcher
import org.balch.orpheus.djapp.ai.tools.playingPulsar
import org.balch.orpheus.djapp.ai.tools.stemRecorder
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.StemRecorder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JvmStemCaptureServiceTest {
    private val pulsar = playingPulsar("Night Drive")
    private val root: File = Files.createTempDirectory("stem-captures").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private suspend fun TestScope.service(): StemCaptureFlow = flow(stemRecorder(pulsar))

    private fun TestScope.flow(
        recorder: StemRecorder,
        openWriter: (File, Int) -> StemWavWriter = ::StemWavWriter,
    ) = StemCaptureFlow({ recorder }, root, OneDispatcher(StandardTestDispatcher(testScheduler)), openWriter)

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.tag(at: Int) = String(ByteArray(4) { get(at + it) }, Charsets.US_ASCII)

    @Test
    fun captureWritesWavsAndManifest() = runTest {
        val r = service().capture(0.5f, listOf(0, 12))
        assertTrue(r.ok, r.message)
        val frames = 24_000
        assertEquals(frames, r.frames)
        assertEquals(0L, r.droppedFrames)

        val dir = File(r.dir)
        assertEquals(root.canonicalFile, dir.parentFile.canonicalFile)
        assertTrue(dir.name.matches(Regex("night_drive-\\d{8}-\\d{6}")), dir.name)
        assertEquals(setOf("track0.wav", "out.wav", "stems.json"), dir.list()!!.toSet())
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        }

        assertEquals(listOf("track0", "out"), r.files.map { it.stem })
        assertEquals(listOf(OrpheusEngineId.VA.displayName, "out"), r.files.map { it.label })
        // The fake stamps float j of frame k with j * 1000 + k: track0 is floats 0-1, out is floats 24-25.
        for ((file, firstL) in r.files.zip(listOf(0f, 24_000f))) {
            assertEquals(File(dir, "${file.stem}.wav").absolutePath, file.path)
            val wav = File(file.path).readBytes()
            assertEquals(44 + frames * 8, wav.size)
            val b = le(wav)
            assertEquals("RIFF", b.tag(0))
            assertEquals(36 + frames * 8, b.getInt(4))
            assertEquals("WAVE", b.tag(8))
            assertEquals("fmt ", b.tag(12))
            assertEquals(16, b.getInt(16))
            assertEquals(3, b.getShort(20).toInt())
            assertEquals(2, b.getShort(22).toInt())
            assertEquals(48_000, b.getInt(24))
            assertEquals(48_000 * 8, b.getInt(28))
            assertEquals(8, b.getShort(32).toInt())
            assertEquals(32, b.getShort(34).toInt())
            assertEquals("data", b.tag(36))
            assertEquals(frames * 8, b.getInt(40))
            assertEquals(firstL, b.getFloat(44))
            assertEquals(firstL + 1000f, b.getFloat(48))
            val last = 44 + (frames - 1) * 8
            assertEquals(firstL + frames - 1, b.getFloat(last))
            assertTrue(file.rmsDb > 0f && file.peakDb >= file.rmsDb, "$file")
        }

        val manifest = Json.parseToJsonElement(File(dir, "stems.json").readText()).jsonObject
        assertEquals("Night Drive", manifest.str("vibe"))
        assertEquals("verse", manifest.str("sectionAtStart"))
        assertEquals(48_000, manifest.getValue("sampleRate").jsonPrimitive.int)
        assertEquals(frames, manifest.getValue("frames").jsonPrimitive.int)
        assertEquals(0L, manifest.getValue("droppedFrames").jsonPrimitive.long)
        val stems = manifest.getValue("stems").jsonArray.map { it.jsonObject }
        assertEquals(listOf("track0", "out"), stems.map { it.str("stem") })
        assertEquals(r.files.map { it.label }, stems.map { it.str("label") })
        assertEquals(r.files.map { it.rmsDb }, stems.map { it.getValue("rmsDb").jsonPrimitive.float })
        assertEquals(r.files.map { it.peakDb }, stems.map { it.getValue("peakDb").jsonPrimitive.float })
    }

    @Test
    fun abortedCaptureLeavesNoFolder() = runTest {
        val service = service()
        val run = async { service.capture(2f, listOf(0, 1)) }
        advanceTimeBy(500)
        assertTrue(root.list()!!.isNotEmpty(), "the capture should be writing")
        pulsar.applyVibe(vibe("Low Tide"))
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("vibe changed" in r.message, r.message)
        assertEquals(emptyList(), root.list()!!.toList())
        assertTrue(r.files.isEmpty())
    }

    @Test
    fun cutShortCaptureLeavesNoFolder() = runTest {
        val tap = FakeStemTap()
        val service = flow(stemRecorder(pulsar, tap))
        val run = async { service.capture(2f, listOf(0, 1)) }
        advanceTimeBy(500)
        assertTrue(root.list()!!.isNotEmpty(), "the capture should be writing")
        tap.engineClosed = true
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("cut short" in r.message, r.message)
        assertEquals(emptyList(), root.list()!!.toList())
    }

    @Test
    fun refusedCaptureLeavesNoFolder() = runTest {
        pulsar.stateFlow.value = pulsar.stateFlow.value.copy(playing = false)
        val r = service().capture(1f, listOf(0))
        assertFalse(r.ok)
        assertTrue("play" in r.message, r.message)
        assertEquals(emptyList(), root.list().orEmpty().toList())
    }

    @Test
    fun refusedSecondCallerTouchesNoDisk() = runTest {
        var opened = 0
        val service = flow(stemRecorder(pulsar)) { f, rate -> opened++; StemWavWriter(f, rate) }
        val first = async { service.capture(2f, listOf(0)) }
        advanceTimeBy(500)
        val before = root.list()!!.toSet()
        assertEquals(1, opened)
        val second = service.capture(0.5f, listOf(0, 1))
        assertFalse(second.ok)
        assertTrue("already running" in second.message, second.message)
        assertEquals(1, opened)
        assertEquals(before, root.list()!!.toSet())
        assertTrue(first.await().ok)
    }

    @Test
    fun cancelledCaptureLeavesNoFolder() = runTest {
        val recorder = stemRecorder(pulsar)
        val service = flow(recorder)
        val run = async { service.capture(2f, listOf(0, 1)) }
        advanceTimeBy(500)
        assertTrue(root.list()!!.isNotEmpty(), "the capture should be writing")
        run.cancelAndJoin()
        assertEquals(emptyList(), root.list()!!.toList())
        // The recorder's lock was released, so the next capture runs.
        val again = service.capture(0.5f, listOf(0))
        assertTrue(again.ok, again.message)
    }

    @Test
    fun ioErrorLeavesNoFolder() = runTest {
        val recorder = stemRecorder(pulsar)
        // A writer whose channel is already closed fails on its first flush, mid-recording.
        val broken = flow(recorder) { f, rate -> StemWavWriter(f, rate).also { it.close() } }
        val r = broken.capture(0.5f, listOf(0, 12))
        assertFalse(r.ok)
        assertTrue("couldn't write the stems" in r.message, r.message)
        assertEquals("", r.dir)
        assertEquals(emptyList(), root.list()!!.toList())
        val again = flow(recorder).capture(0.5f, listOf(0))
        assertTrue(again.ok, again.message)
    }

    @Test
    fun stalePartialsAreSweptYoungOnesKept() = runTest {
        val now = System.currentTimeMillis()
        fun dir(name: String, ageMin: Int) = File(root, name).apply {
            mkdirs()
            File(this, "track0.wav").writeText("x")
            setLastModified(now - ageMin * 60_000L)
        }
        val stale = dir(".partial-stale", 11)
        val young = dir(".partial-young", 1)
        val oldCapture = dir("night_drive-20260101-000000", 11)
        val r = service().capture(0.5f, listOf(0))
        assertTrue(r.ok, r.message)
        assertFalse(stale.exists())
        assertTrue(young.exists())
        assertTrue(oldCapture.exists())
        assertTrue(File(r.dir).exists())
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
