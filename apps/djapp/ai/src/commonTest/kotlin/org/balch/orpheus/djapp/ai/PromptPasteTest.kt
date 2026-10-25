package org.balch.orpheus.djapp.ai

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptPasteTest {
    @Test fun typedEnterSubmits() = assertEquals(DraftEdit.Text("reggae", submit = true), classifyDraftEdit("reggae", "reggae\n"))

    @Test fun typingIsText() = assertEquals(DraftEdit.Text("regg", false), classifyDraftEdit("reg", "regg"))

    @Test fun shortMultilinePasteJoinsWithoutSubmitting() =
        assertEquals(DraftEdit.Text("make it dub heavy", false), classifyDraftEdit("make it ", "make it dub\nheavy"))

    @Test fun longPasteBecomesAReference() {
        val tab = (1..6).joinToString("\n") { "e|---$it---|" }
        assertEquals(DraftEdit.Reference(tab, "like this "), classifyDraftEdit("like this ", "like this $tab"))
    }

    @Test fun hugeSingleLinePasteBecomesAReference() {
        val html = "<html>" + "x".repeat(2_000) + "</html>"
        assertIs<DraftEdit.Reference>(classifyDraftEdit("", html))
    }

    @Test fun promptNamesThePasteId() {
        val p = agentPrompt("dubby please", SongPasteRef("paste-2", 120, "Kettle Morning"))
        assertTrue(p.startsWith("dubby please\n\nThe user attached a song reference"), p)
        assertTrue("pasteId=\"paste-2\"" in p)
        assertTrue("song_reference" in p)
        assertEquals("dubby please", agentPrompt("dubby please", null))
        assertTrue(agentPrompt("", SongPasteRef("paste-1", 3, null)).startsWith("Make a Pulsar vibe inspired by the attached song reference"))
    }

    @Test fun promptTitleIsCappedAndCleaned() {
        val p = agentPrompt("", SongPasteRef("paste-1", 3, "Kettle \"Morning\"\n" + "steam ".repeat(80)))
        val named = p.substringAfter("3 lines, \"").substringBefore("\").")
        assertTrue(named.length <= 80, named); assertTrue('"' !in named && '\n' !in named, named)
        assertTrue(named.startsWith("Kettle Morning steam"), named)
    }

    private fun sheet(n: Int) = "Kettle Morning $n\nAm D\nWake up slow"

    @Test fun attachParsesOnItsDispatcherAndKeepsOnlyTheNewest() = runTest {
        val store = SongPasteStore()
        val state = MutableStateFlow(DjAiUiState())
        val attacher = ReferenceAttacher(store, this, StandardTestDispatcher(testScheduler), state)
        attacher.attach(sheet(1))
        // Nothing is parsed on the caller: the chip appears once the dispatcher runs.
        assertNull(state.value.reference); assertNull(store.latest)
        attacher.attach(sheet(2))
        advanceUntilIdle()
        assertEquals("Kettle Morning 2", state.value.reference?.title); assertEquals(listOf(state.value.reference!!.id), store.ids())
        attacher.attach(sheet(3))
        advanceUntilIdle()
        assertEquals("Kettle Morning 3", state.value.reference?.title); assertEquals(listOf(state.value.reference!!.id), store.ids())
        attacher.attach(sheet(4))
        attacher.detach()
        advanceUntilIdle()
        assertNull(state.value.reference); assertEquals(emptyList(), store.ids())
    }

    @Test fun enterWhileAPasteParsesWaitsForItsChipAndSendsOnce() = runTest {
        val store = SongPasteStore()
        val state = MutableStateFlow(DjAiUiState())
        val attacher = ReferenceAttacher(store, this, StandardTestDispatcher(testScheduler), state)
        val sent = mutableListOf<SongPasteRef?>()
        attacher.attach(sheet(1))
        // Enter twice before the parse lands: nothing goes out yet, then one send carrying the chip.
        repeat(2) { attacher.whenAttached { sent += state.value.reference } }
        assertEquals(emptyList(), sent); assertNotNull(state.value.parsing)
        advanceUntilIdle()
        assertEquals(listOf("Kettle Morning 1"), sent.map { it?.title }); assertNull(state.value.parsing)
        attacher.whenAttached { sent += state.value.reference }
        assertEquals(2, sent.size)
    }

    @Test fun detachDuringTheChipWriteLeavesNoChip() = runTest {
        val store = SongPasteStore()
        lateinit var attacher: ReferenceAttacher
        val state = DetachMidWrite(MutableStateFlow(DjAiUiState())) { attacher.detach() }
        attacher = ReferenceAttacher(store, this, StandardTestDispatcher(testScheduler), state)
        attacher.attach(sheet(1))
        advanceUntilIdle()
        assertNull(state.value.reference); assertEquals(emptyList(), store.ids())
    }

    @Test fun shareWaitsForPendingSend() = runTest {
        val store = SongPasteStore()
        val state = MutableStateFlow(DjAiUiState())
        val attacher = ReferenceAttacher(store, this, StandardTestDispatcher(testScheduler), state)
        var sent: SongPasteRef? = null
        // The paste stays mid-parse until the scheduler runs; the Enter waits on it.
        attacher.attach(sheet(1))
        attacher.whenAttached { sent = state.value.reference }
        attacher.attachAfterPendingSend("Shared Tab\nG C D\nEm\nAm")
        assertNull(sent); assertNull(state.value.reference)
        advanceUntilIdle()
        assertEquals("Kettle Morning 1", sent?.title)
        assertEquals("Shared Tab", state.value.reference?.title)
    }

    private fun lines(n: Int) = (1..n).joinToString("\n") { "ab" }

    @Test fun compositeCommitWithOneTrailingNewlineSubmits() =
        assertEquals(DraftEdit.Text("reggae", true), classifyDraftEdit("regga", "reggae\n"))

    @Test fun enterInTheMiddleSubmitsAndDropsTheNewline() =
        assertEquals(DraftEdit.Text("ab", true), classifyDraftEdit("ab", "a\nb"))

    // Lines the user added with Shift+Enter stay when Enter submits or a short paste joins.
    @Test fun enterKeepsEarlierLines() =
        assertEquals(DraftEdit.Text("one\ntwo", true), classifyDraftEdit("one\ntwo", "one\ntwo\n"))

    @Test fun shortPasteJoinsOnlyItself() =
        assertEquals(DraftEdit.Text("one\nab cd", false), classifyDraftEdit("one\n", "one\nab\ncd"))

    @Test fun shortTrailingNewlinePasteWithMoreNewlinesJoins() =
        assertEquals(DraftEdit.Text("x a b ", false), classifyDraftEdit("x ", "x a\nb\n"))

    @Test fun longTrailingNewlinePasteIsAReference() {
        val tab = "e".repeat(250) + "\n"
        assertIs<DraftEdit.Reference>(classifyDraftEdit("", tab))
    }

    @Test fun deletionAndEmptyInputsAreText() {
        assertEquals(DraftEdit.Text("re", false), classifyDraftEdit("reg", "re"))
        assertEquals(DraftEdit.Text("", false), classifyDraftEdit("reg", ""))
        assertEquals(DraftEdit.Text("", false), classifyDraftEdit("", ""))
    }

    @Test fun replacingASelectionWithALongPasteIsAReference() {
        val tab = lines(6)
        assertEquals(DraftEdit.Reference(tab, "a XX c"), classifyDraftEdit("a XX c", "a $tab c"))
    }

    @Test fun lineBoundary() {
        assertIs<DraftEdit.Text>(classifyDraftEdit("", lines(3)))
        assertIs<DraftEdit.Reference>(classifyDraftEdit("", lines(4)))
    }

    @Test fun charBoundaryWithNewline() {
        assertIs<DraftEdit.Text>(classifyDraftEdit("", "a\n" + "b".repeat(197)))
        assertIs<DraftEdit.Reference>(classifyDraftEdit("", "a\n" + "b".repeat(198)))
    }

    @Test fun charBoundaryWithoutNewline() {
        assertEquals(1999, (classifyDraftEdit("", "x".repeat(1999)) as DraftEdit.Text).draft.length)
        assertIs<DraftEdit.Reference>(classifyDraftEdit("", "x".repeat(2000)))
    }
}

// Runs [onWrite] once, just before the first write that would show a chip, as a detach from the UI thread could.
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DetachMidWrite(
    private val real: MutableStateFlow<DjAiUiState>,
    private val onWrite: () -> Unit,
) : MutableStateFlow<DjAiUiState> by real {
    private var fired = false

    override fun compareAndSet(expect: DjAiUiState, update: DjAiUiState): Boolean {
        if (!fired && update.reference != null) { fired = true; onWrite() }
        return real.compareAndSet(expect, update)
    }
}
