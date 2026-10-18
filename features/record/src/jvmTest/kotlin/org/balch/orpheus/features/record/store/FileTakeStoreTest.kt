package org.balch.orpheus.features.record.store

import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.presets.PresetJson
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.record.telemetry.GateOn
import org.balch.orpheus.features.record.telemetry.TelemetryJson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.balch.orpheus.features.record.telemetry.EffectsSnapshot
import org.balch.orpheus.features.record.telemetry.TakeSnapshot
import org.balch.orpheus.features.record.telemetry.TakeTelemetry
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTakeStoreTest {

    private lateinit var root: File
    private lateinit var store: FileTakeStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("takes").toFile()
        store = FileTakeStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val snapshot = TakeSnapshot(
        bpm = 120f, pairs = emptyList(), quads = emptyList(),
        effects = EffectsSnapshot(0f, 0f, 0f, 0f, 0f, 0f, 0f),
    )

    private fun telemetry(id: String, durationSec: Float) = TakeTelemetry(
        takeId = id, sampleRate = 48000, durationSec = durationSec, droppedFrames = 0,
        start = snapshot, end = snapshot, events = emptyList(),
    )

    private fun finishTake(id: String, durationSec: Float, modifiedMs: Long) {
        store.open(id, 48000).apply {
            writeAudio(FloatArray(480 * 2), 480)
            finish(SynthPreset(name = "rec-$id"), telemetry(id, durationSec))
        }
        File(root, id).setLastModified(modifiedMs)
    }

    @Test
    fun finishWritesAllThreeFiles() {
        finishTake("take-a", 2.5f, 1_000L)
        val dir = File(root, "take-a")
        assertTrue(File(dir, "audio.wav").isFile)
        assertTrue(File(dir, "preset.json").readText().contains("rec-take-a"))
        assertTrue(File(dir, "telemetry.json").readText().contains("\"takeId\""))
    }

    @Test
    fun listNewestFirstAndSkipsBrokenFolders() = runTest {
        finishTake("take-old", 1f, 1_000_000L)
        finishTake("take-new", 2f, 2_000_000L)
        File(root, "junk").mkdirs()
        File(root, "stray.txt").writeText("x")

        val takes = store.list()

        assertEquals(listOf("take-new", "take-old"), takes.map { it.takeId })
        assertEquals(2f, takes[0].durationSec)
        assertEquals(2_000_000L, takes[0].createdAtMs)
        assertEquals(File(root, "take-new/audio.wav").absolutePath, takes[0].audioPath)
    }

    @Test
    fun listFallsBackToWavLengthWithoutTelemetry() = runTest {
        val dir = File(root, "take-bare").apply { mkdirs() }
        WavFileWriter(File(dir, "audio.wav"), 48000).apply {
            write(FloatArray(48000 * 2), 48000)
            close()
        }

        assertEquals(1f, store.list().single().durationSec)
    }

    @Test
    fun listFallsBackToWavLengthWhenTelemetryUnreadable() = runTest {
        finishTake("take-bad", 9f, 1_000L)
        File(root, "take-bad/telemetry.json").writeText("{ not json")

        assertEquals(480f / 48000f, store.list().single().durationSec)
    }

    @Test
    fun deleteRemovesFolder() = runTest {
        finishTake("take-a", 1f, 1_000L)
        finishTake("take-b", 1f, 2_000L)

        store.delete("take-a")

        assertFalse(File(root, "take-a").exists())
        assertEquals(listOf("take-b"), store.list().map { it.takeId })
    }

    @Test
    fun deleteIgnoresIdsThatEscapeTheRoot() = runTest {
        val outside = File(root.parentFile, "outside-${root.name}").apply { mkdirs() }
        try {
            store.delete("../${outside.name}")
            assertTrue(outside.exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun abortRemovesHalfWrittenTake() {
        val writer = store.open("take-half", 48000)
        writer.writeAudio(FloatArray(480 * 2), 480)
        assertTrue(File(root, "take-half").exists())

        writer.abort()

        assertFalse(File(root, "take-half").exists())
    }

    @Test
    fun openFailsFastWhenFolderExists() {
        finishTake("take-dup", 1f, 1_000L)
        val before = File(root, "take-dup/preset.json").readText()

        assertFailsWith<IllegalStateException> { store.open("take-dup", 48000) }

        assertEquals(before, File(root, "take-dup/preset.json").readText())
    }

    @Test
    fun finishWritesJsonThatRoundTrips() {
        val preset = SynthPreset(
            name = "rec-take-rt", bpm = 97f, createdAt = 42L,
            portValues = mapOf("a" to PortValue.FloatValue(0.5f), "b" to PortValue.IntValue(3), "c" to PortValue.BoolValue(true)),
        )
        val tel = telemetry("take-rt", 3f).copy(events = listOf(GateOn(0.1f, 2, 60f)))
        store.open("take-rt", 48000).apply {
            writeAudio(FloatArray(480 * 2), 480)
            finish(preset, tel)
        }

        val dir = File(root, "take-rt")
        assertEquals(preset, PresetJson.decodeFromString<SynthPreset>(File(dir, "preset.json").readText()))
        assertEquals(tel, TelemetryJson.decodeFromString<TakeTelemetry>(File(dir, "telemetry.json").readText()))
    }

    @Test
    fun finishDuringConcurrentWritesKeepsWavConsistent() {
        val writer = store.open("take-race", 48000)
        val error = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)
        val chunk = FloatArray(1000 * 2) { 0.1f }
        val t = thread {
            try {
                started.countDown()
                repeat(20_000) { writer.writeAudio(chunk, 1000) }
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        started.await()
        Thread.sleep(5)
        writer.finish(SynthPreset(name = "rec-take-race"), telemetry("take-race", 1f))
        t.join()

        assertNull(error.get())
        val bytes = File(root, "take-race/audio.wav").readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val data = buf.getInt(40)
        assertTrue(data > 0)
        assertEquals(0, data % 8)
        assertEquals(bytes.size - 44, data)
        assertEquals(bytes.size - 8, buf.getInt(4))
    }
}
