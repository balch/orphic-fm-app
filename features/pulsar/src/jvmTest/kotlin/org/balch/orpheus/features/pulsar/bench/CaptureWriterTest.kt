package org.balch.orpheus.features.pulsar.bench

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.VibeCatalog
import org.balch.orpheus.features.pulsar.vibes.VibeStatus

class CaptureWriterTest {
    private val vibesDir = File("src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")
    private val vibe = mkMinimalVibe("Orphic Lore")
    private val row = CaptureRow(Scope.VIBE, null, null, 2, CaptureField.PAN, null, 0.25f, 0.75f)

    private fun writer(root: File) = CaptureWriter(File(root, "build/mix-bench/captures"), root)

    private fun fakeRepo(): File {
        val root = Files.createTempDirectory("capture").toFile()
        val dir = File(root, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")
        vibesDir.copyRecursively(dir)
        return root
    }

    @Test fun findsOrphicLore() {
        assertEquals("OrphicLoreVibe.kt", findVibeSource(vibesDir, "Orphic Lore")?.name)
    }

    @Test fun everyLiveVibeHasASource() {
        val live = VibeCatalog.entries.filterValues { it.status == VibeStatus.LIVE }.keys.map { it.value }
        assertTrue(live.isNotEmpty(), "no LIVE vibes in the catalog")
        val missing = live.filter { findVibeSource(vibesDir, it) == null }
        assertTrue(missing.isEmpty(), "no source file found for: $missing")
    }

    @Test fun findsVibeInMultiVibeFile() {
        assertEquals("FireSkyBackupVibes.kt", findVibeSource(vibesDir, "Fire Sky .5f")?.name)
        assertEquals("FifthSymphonyVibe.kt", findVibeSource(vibesDir, "Symphony No. 5 in C Minor")?.name)
        assertNull(findVibeSource(vibesDir, "No Such Vibe"))
    }

    @Test fun staleWhenHashDiffers() {
        val root = fakeRepo()
        val w = writer(root)
        val src = findVibeSource(File(root, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes"), "Orphic Lore")!!
        val fresh = w.write(vibe, listOf(row), sha256(src))
        assertTrue("\"stale\":false" in Json.parseToJsonElement(fresh.file.readText()).toString())
        assertEquals(false, fresh.capture.stale)
        val stale = w.write(vibe, listOf(row), "deadbeef")
        assertTrue("\"stale\":true" in Json.parseToJsonElement(stale.file.readText()).toString())
        assertEquals(true, stale.capture.stale)
    }

    @Test fun rowsRoundTrip() {
        val root = fakeRepo()
        val written = writer(root).write(vibe, listOf(row), "x")
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(CaptureFile.serializer(), written.file.readText())
        assertEquals(written.capture, back)
        assertEquals(listOf(row), back.rows)
        assertEquals("orphic_lore", back.slug)
        assertEquals("features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/OrphicLoreVibe.kt", back.source)
    }

    @Test fun captureWritesVibeJsonWithoutRepo() {
        val dir = File(Files.createTempDirectory("captures").toFile(), "mix-captures")
        val written = CaptureWriter(dir, repoRoot = null).write(vibe, listOf(row), baselineHash = null)
        assertEquals(dir, written.file.parentFile)
        assertTrue(written.file.name.matches(Regex("""orphic_lore-\d{8}-\d{6}\.json""")), written.file.name)
        val back = Json.decodeFromString(CaptureFile.serializer(), written.file.readText())
        assertEquals(written.capture, back)
        assertNull(back.source)
        assertNull(back.baselineHash)
        assertNull(back.currentHash)
        assertNull(back.stale)
        assertFalse(back.aiVibe)
        assertEquals(vibe, Json.decodeFromString(Vibe.serializer(), back.vibeJson))
        assertNull(CaptureWriter(dir, repoRoot = null).sourceHash("Orphic Lore"))
    }

    @Test fun captureKeepsSourceWithRepo() {
        val root = fakeRepo()
        val dir = Files.createTempDirectory("app-captures").toFile()
        val w = CaptureWriter(dir, root)
        val hash = assertNotNull(w.sourceHash("Orphic Lore"))
        val written = w.write(vibe, listOf(row), hash)
        assertEquals(dir, written.file.parentFile)
        val back = Json.decodeFromString(CaptureFile.serializer(), written.file.readText())
        assertEquals("features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/OrphicLoreVibe.kt", back.source)
        assertEquals(hash, back.baselineHash)
        assertEquals(hash, back.currentHash)
        assertEquals(false, back.stale)
        assertEquals(vibe, Json.decodeFromString(Vibe.serializer(), back.vibeJson))
        // An AI vibe has no source file, even with a repo, so nothing can go stale.
        val ai = w.write(vibe.copy(name = "Velvet Static"), listOf(row), null, aiVibe = true).capture
        assertTrue(ai.aiVibe)
        assertNull(ai.source)
        assertNull(ai.stale)
    }

    @Test fun unknownBaselineIsNotStaleButUnknown() {
        val written = writer(fakeRepo()).write(vibe, listOf(row), baselineHash = null)
        assertNotNull(written.capture.currentHash)
        assertNull(written.capture.stale)
    }

    @Test fun olderCaptureFilesStillDecode() {
        val old = """{"vibe":"Orphic Lore","slug":"orphic_lore","source":null,"baselineHash":"x","currentHash":null,""" +
            """"stale":false,"capturedAt":"2026-10-20T10:00","rows":[]}"""
        val back = Json.decodeFromString(CaptureFile.serializer(), old)
        assertFalse(back.aiVibe)
        assertEquals("", back.vibeJson)
    }

    @Test fun sameSecondCapturesDoNotOverwrite() {
        val w = writer(fakeRepo())
        val files = (1..3).map { w.write(vibe, listOf(row), "x").file }
        assertEquals(3, files.map { it.name }.toSet().size)
        assertNotEquals(files[0], files[1])
    }
}
