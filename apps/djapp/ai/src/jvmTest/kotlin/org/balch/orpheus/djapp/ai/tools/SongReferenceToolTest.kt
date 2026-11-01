package org.balch.orpheus.djapp.ai.tools

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.balch.orpheus.djapp.ai.mcp.McpToolAdapter
import org.balch.orpheus.djapp.ai.songref.JvmSongFileReader
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import org.balch.orpheus.djapp.ai.songref.SongReference
import org.balch.orpheus.djapp.ai.songref.SyntheticSong
import org.balch.orpheus.djapp.ai.songref.UnavailableSongFileReader
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SongReferenceToolTest {

    private val dir: File = Files.createTempDirectory("song-reference-tool").toFile()
    private val store = SongPasteStore()
    private val tool = SongReferenceTool(store, JvmSongFileReader(OneDispatcher(Dispatchers.Unconfined)))

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun run(args: SongReferenceArgs, t: SongReferenceTool = tool): SongReference =
        runBlocking { t.tool.execute(args) }

    private fun file(name: String, content: String = SyntheticSong.ugPage()): File =
        File(dir, name).also { it.writeText(content) }

    @Test fun latestPasteIsTheDefault() {
        store.put(SyntheticSong.ugPage())
        val r = run(SongReferenceArgs())
        assertTrue(r.ok, r.message)
        assertEquals("A", r.key?.rootNote?.name)
        assertEquals("DORIAN", r.key?.scaleType?.name)
    }

    @Test fun pasteById() {
        val first = store.put(SyntheticSong.ugPage())
        store.put(SyntheticSong.PLAIN)
        val r = run(SongReferenceArgs(pasteId = first.id))
        assertTrue(r.ok, r.message)
        assertEquals("Kettle Morning", r.source!!.title)
    }

    @Test fun unknownPasteIdListsKnownOnes() {
        store.put(SyntheticSong.ugPage())
        val r = run(SongReferenceArgs(pasteId = "paste-9"))
        assertFalse(r.ok)
        assertTrue("paste-1" in r.message, r.message)
    }

    @Test fun textArgument() {
        val r = run(SongReferenceArgs(text = SyntheticSong.PLAIN))
        assertTrue(r.ok, r.message)
        assertEquals("plain", r.source!!.format)
    }

    @Test fun twoSourcesAreRefused() {
        val p = store.put(SyntheticSong.ugPage())
        val r = run(SongReferenceArgs(text = SyntheticSong.PLAIN, pasteId = p.id))
        assertFalse(r.ok)
        assertTrue("one of pasteId, text or path" in r.message, r.message)
    }

    @Test fun nothingToReadIsRefused() {
        val r = run(SongReferenceArgs())
        assertFalse(r.ok)
        assertTrue("paste" in r.message, r.message)
        assertTrue("into the chat" in r.message, r.message)
    }

    @Test fun filePath() {
        val r = run(SongReferenceArgs(path = file("page.html").absolutePath))
        assertTrue(r.ok, r.message)
        assertEquals("Kettle Morning", r.source!!.title)
    }

    @Test fun disallowedFileTypeIsRefused() {
        val key = file("secrets.key")
        val bare = file("noext")
        listOf(key, bare).forEach {
            val r = run(SongReferenceArgs(path = it.absolutePath))
            assertFalse(r.ok, it.name)
            assertTrue(it.absolutePath in r.message, r.message)
        }
    }

    @Test fun uppercaseExtensionIsAllowed() {
        val r = run(SongReferenceArgs(path = file("PAGE.HTML").absolutePath))
        assertTrue(r.ok, r.message)
    }

    @Test fun missingFileIsRefused() {
        val path = File(dir, "nope.txt").absolutePath
        val r = run(SongReferenceArgs(path = path))
        assertFalse(r.ok)
        assertTrue(path in r.message, r.message)
    }

    @Test fun tooLargeIsRefused() {
        val r = run(SongReferenceArgs(text = "a".repeat(2_000_001)))
        assertFalse(r.ok)
        assertTrue("2000000" in r.message, r.message)
    }

    @Test fun unavailableReaderSaysPassText() {
        val t = SongReferenceTool(store, UnavailableSongFileReader())
        val r = run(SongReferenceArgs(path = "/x/page.html"), t)
        assertFalse(r.ok)
        assertTrue("text" in r.message, r.message)
    }

    @Test fun removeUnknownIsANoOpAndLatestIsNewest() {
        val a = store.put(SyntheticSong.ugPage())
        val b = store.put(SyntheticSong.PLAIN)
        store.remove("paste-99")
        assertEquals(b, store.latest)
        store.remove(b.id)
        assertEquals(a, store.latest)
    }

    @Test fun storeKeepsTheNewestFour() {
        val ids = (1..5).map { store.put("line $it").id }
        assertEquals(null, store.get(ids[0]))
        assertEquals(ids[4], store.latest!!.id)
        assertEquals("paste-5", ids[4])
    }

    @Test fun requestedKeyPassesThrough() {
        val r = run(SongReferenceArgs(text = SyntheticSong.PLAIN, rootNote = RootNote.C, scaleType = ScaleType.MAJOR))
        assertTrue(r.ok, r.message)
        assertEquals("request", r.key?.from)
    }

    @Test fun worksOverMcpWithAFilePath() {
        val page = file("page.html")
        val result = runBlocking { McpToolAdapter.call(tool.tool, buildJsonObject { put("path", page.absolutePath) }) }
        assertNotEquals(true, result.isError)
        val json = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals("A", json["key"]!!.jsonObject["rootNote"]!!.jsonPrimitive.content)
    }

    @Test fun mcpSchemaListsTheArguments() {
        val schema = McpToolAdapter.mcpTool(tool.tool).inputSchema
        listOf("pasteId", "text", "path", "rootNote", "scaleType").forEach { assertTrue(it in schema.properties!!, it) }
        assertTrue(schema.required.isNullOrEmpty())
    }

    @Test fun toolIsNamedSongReference() =
        assertEquals("song_reference", SongReferenceTool(SongPasteStore(), UnavailableSongFileReader()).tool.descriptor.name)

    @Test fun descriptionSaysHowAFailureReads() {
        val d = tool.tool.descriptor.description
        assertTrue("ok is false" in d && "message" in d && "retry" in d, d)
    }

    @Test fun longPasteTitleIsCappedAndCleaned() {
        val raw = "Kettle \"Morning\"\n" + "steam ".repeat(80)
        assertTrue(raw.length >= 480)
        val title = store.put(SyntheticSong.ugPage(title = raw)).title!!
        assertTrue(title.length <= 80, title); assertTrue('"' !in title && '\n' !in title, title)
        assertTrue(title.startsWith("Kettle Morning steam"), title)
    }

    @Test fun resultFieldsTheGuideSectionNames() {
        val fields = SongReference.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        listOf("key", "chords", "sections", "phraseSlots", "melodies").forEach { assertTrue(it in fields, it) }
    }

    @Test fun refusalDoesNotRevealWhetherTheFileExists() {
        val existing = file("secrets.key")
        val missing = File(dir, "missing").resolve("secrets.key")
        val a = run(SongReferenceArgs(path = existing.absolutePath)).message
        val b = run(SongReferenceArgs(path = missing.absolutePath)).message
        assertEquals(a.replace(existing.absolutePath, "P"), b.replace(missing.absolutePath, "P"))
        assertTrue("only" in a, a)
    }

    @Test fun symlinkToADisallowedFileIsRefused() {
        val target = file("secret.key")
        val link = File(dir, "notes.txt")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: UnsupportedOperationException) {
            return
        } catch (e: java.io.IOException) {
            return
        }
        val r = run(SongReferenceArgs(path = link.absolutePath))
        assertFalse(r.ok)
        assertTrue(link.absolutePath in r.message, r.message)
    }

    @Test fun directoryIsRefused() {
        val d = File(dir, "folder.txt").also { it.mkdir() }
        val r = run(SongReferenceArgs(path = d.absolutePath))
        assertFalse(r.ok)
        assertTrue(d.absolutePath in r.message, r.message)
    }

    @Test fun tildeExpandsToTheHomeDirectory() {
        val home = File(System.getProperty("user.home"))
        val f = File(home, "song-reference-tool-test-${System.nanoTime()}.txt")
        try {
            f.writeText(SyntheticSong.PLAIN)
            val r = run(SongReferenceArgs(path = "~/${f.name}"))
            assertTrue(r.ok, r.message)
        } finally {
            f.delete()
        }
    }

    @Test fun blankFieldsAreAbsent() {
        val r = run(SongReferenceArgs(pasteId = "", text = SyntheticSong.PLAIN, path = " "))
        assertTrue(r.ok, r.message)
        assertEquals("plain", r.source?.format)
    }

    @Test fun latestIsAlwaysTheNewestIdUnderContention() {
        val threads = (1..8).map { Thread { repeat(50) { store.put("x") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("paste-400", store.latest?.id)
        assertEquals(4, (1..400).count { store.get("paste-$it") != null })
    }
}
