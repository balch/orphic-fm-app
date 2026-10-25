package org.balch.orpheus.djapp.ai.collab

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.LayerWrite
import org.balch.orpheus.features.pulsar.bench.Macro

class MixChangeStoreTest {
    private val dir: File = Files.createTempDirectory("mix-changes").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val night = vibe("Night Drive")

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store() = FileMixChangeStore(dir, scope)

    private fun history() = MixHistory()
        .record(EditOrigin.USER, MixControl.Level(2), null, null, "0.0 dB", "-3.0 dB", mapOf(LayerField.VibeLevel(2) to LayerWrite(null, -3f)), 1_000)
        .mark(2_000)
        .record(
            EditOrigin.AGENT, MixControl.MacroKnob(Macro.ENERGY), 1, "verse", "0.50", "0.80",
            mapOf(LayerField.SectionMacro(1, Macro.ENERGY) to LayerWrite(null, 0.8f)), 3_000,
        )
        .capture(4_000, "Capture 1", "night_drive-1.json")

    private val file get() = File(dir, "night_drive.json")

    @Test
    fun putThenGetRoundTrips() {
        val s = store()
        val h = history()
        s.put(night, h)
        val got = s.get(night)!!
        assertEquals(h.items, got.items)
        assertEquals(h.nextId, got.nextId)
        assertEquals(h.pointCount, got.pointCount)
        assertEquals(h.layers(), got.layers())
    }

    @Test
    fun getAfterNewInstanceReadsDisk() = runBlocking {
        val first = store()
        first.put(night, history())
        first.flush()
        assertTrue(file.isFile)
        val got = store().get(night)!!
        assertEquals(history().items, got.items)
        assertEquals(history().pointCount, got.pointCount)
    }

    @Test
    fun changedVibeContentDropsAndDeletesFile() = runBlocking {
        store().apply { put(night, history()); flush() }
        val edited = night.copy(bpm = 96f)
        val s = store()
        assertNull(s.get(edited))
        s.flush()
        assertFalse(file.exists())
        assertNull(s.get(night))
    }

    @Test
    fun emptyHistoryDeletesFile() = runBlocking {
        val s = store()
        s.put(night, history())
        s.flush()
        assertTrue(file.isFile)
        s.put(night, MixHistory())
        s.flush()
        assertFalse(file.exists())
        assertNull(s.get(night))
        assertNull(store().get(night))
    }

    @Test
    fun offChangeRoundTripsOff() = runBlocking {
        val h = history().let { it.toggle(it.changes.first().id) }
        store().apply { put(night, h); flush() }
        val got = store().get(night)!!
        assertEquals(listOf(false, true), got.changes.map { it.on })
        assertEquals(h.layers(), got.layers())
    }

    @Test
    fun corruptFileReadsAsNone() {
        dir.mkdirs()
        file.writeText("{ not json")
        assertNull(store().get(night))
    }
}
