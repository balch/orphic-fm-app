package org.balch.orpheus.djapp.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiVibeFolderTest {
    private val root: File = Files.createTempDirectory("ai-vibes-test").toFile()
    private val dir = File(root, "ai-vibes").apply { mkdirs() }

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun json(name: String) = vibeApplyJson.encodeToString(Vibe.serializer(), vibe(name))
    private fun put(fileName: String, body: String) = File(dir, fileName).writeText(body)

    // IO runs on the test's own dispatcher, so the start-up scan has finished once the scheduler idles.
    private fun TestScope.folder(now: () -> Long = { 0L }): AiVibeFolder {
        val io = UnconfinedTestDispatcher(testScheduler)
        return AiVibeFolder(FileAiVibeDir(dir), io, backgroundScope, now).also { advanceUntilIdle() }
    }

    @Test
    fun listsNewestFirstOnePerName() = runTest {
        put("100_Velvet_Static.json", json("Velvet Static"))
        put("300_Ghost_Signal.json", json("Ghost Signal"))
        put("200_velvet_static.json", json("velvet static"))
        val saved = folder().saved.value
        assertEquals(listOf("Ghost Signal" to 300L, "velvet static" to 200L), saved.map { it.name to it.savedAtMillis })
    }

    @Test
    fun skipsUnreadableFiles() = runTest {
        put("100_Broken.json", "{ not json")
        put("200_Stale.json", """{"name":"Stale"}""")
        put("300_Fine.json", json("Fine"))
        put("notes.txt", "hello")
        assertEquals(listOf("Fine"), folder().saved.value.map { it.name })
        assertTrue(File(dir, "100_Broken.json").exists(), "an unreadable file is never removed")
    }

    @Test
    fun deleteMovesEveryVersionToDeleted() = runTest {
        put("100_Velvet_Static.json", json("Velvet Static"))
        put("200_Velvet_Static.json", json("Velvet Static"))
        put("300_Ghost_Signal.json", json("Ghost Signal"))
        val folder = folder()
        folder.delete("VELVET STATIC")
        advanceUntilIdle()
        assertEquals(listOf("Ghost Signal"), folder.saved.value.map { it.name })
        assertEquals(listOf("300_Ghost_Signal.json"), dir.listFiles { f -> f.isFile }!!.map { it.name })
        assertEquals(listOf("100_Velvet_Static.json", "200_Velvet_Static.json"), File(dir, "deleted").list()!!.sorted())
    }

    @Test
    fun archiveWriteRefreshesTheList() = runTest {
        var clock = 1_000L
        val folder = folder { clock++ }
        assertTrue(folder.archive("Ghost Signal", json("Ghost Signal")))
        assertTrue(folder.archive("Velvet Static", json("Velvet Static")))
        assertEquals(listOf("Velvet Static" to 1_001L, "Ghost Signal" to 1_000L), folder.saved.value.map { it.name to it.savedAtMillis })
        assertTrue(File(dir, "1000_Ghost_Signal.json").exists())
    }

    @Test
    fun deletedFolderIsNotListed() = runTest {
        File(dir, "deleted").mkdirs()
        File(dir, "deleted/100_Gone.json").writeText(json("Gone"))
        put("200_Here.json", json("Here"))
        assertEquals(listOf("Here"), folder().saved.value.map { it.name })
    }

    // A fresh install has no folder until the first save.
    @Test
    fun noFolderNoVibes() = runTest {
        dir.deleteRecursively()
        assertEquals(emptyList(), folder().saved.value)
    }
}
