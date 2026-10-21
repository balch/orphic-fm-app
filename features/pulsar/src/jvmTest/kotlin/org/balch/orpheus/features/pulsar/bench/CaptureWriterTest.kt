package org.balch.orpheus.features.pulsar.bench

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.mkMinimalVibe

class CaptureWriterTest {
    private val vibesDir = File("src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")
    private val vibe = mkMinimalVibe("Orphic Lore")
    private val row = CaptureRow(Scope.VIBE, null, null, 2, CaptureField.PAN, null, 0.25f, 0.75f)

    private fun fakeRepo(): File {
        val root = Files.createTempDirectory("capture").toFile()
        val dir = File(root, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes")
        vibesDir.copyRecursively(dir)
        return root
    }

    @Test fun findsOrphicLore() {
        assertEquals("OrphicLoreVibe.kt", findVibeSource(vibesDir, "Orphic Lore")?.name)
    }

    @Test fun findsVibeInMultiVibeFile() {
        assertEquals("CompLabVibe.kt", findVibeSource(vibesDir, "Comp Jazz")?.name)
        assertEquals("FifthSymphonyVibe.kt", findVibeSource(vibesDir, "Symphony No. 5 in C Minor")?.name)
        assertNull(findVibeSource(vibesDir, "No Such Vibe"))
    }

    @Test fun staleWhenHashDiffers() {
        val root = fakeRepo()
        val w = CaptureWriter(root)
        val src = findVibeSource(File(root, "features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes"), "Orphic Lore")!!
        val fresh = Json.parseToJsonElement(w.write(vibe, listOf(row), sha256(src)).readText()).toString()
        assertTrue("\"stale\":false" in fresh)
        val stale = Json.parseToJsonElement(w.write(vibe, listOf(row), "deadbeef").readText()).toString()
        assertTrue("\"stale\":true" in stale)
    }

    @Test fun rowsRoundTrip() {
        val root = fakeRepo()
        val file = CaptureWriter(root).write(vibe, listOf(row), "x")
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(CaptureFile.serializer(), file.readText())
        assertEquals(listOf(row), back.rows)
        assertEquals("orphic_lore", back.slug)
        assertEquals("features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/OrphicLoreVibe.kt", back.source)
    }

    @Test fun sameSecondCapturesDoNotOverwrite() {
        val w = CaptureWriter(fakeRepo())
        val files = (1..3).map { w.write(vibe, listOf(row), "x") }
        assertEquals(3, files.map { it.name }.toSet().size)
        assertNotEquals(files[0], files[1])
    }
}
