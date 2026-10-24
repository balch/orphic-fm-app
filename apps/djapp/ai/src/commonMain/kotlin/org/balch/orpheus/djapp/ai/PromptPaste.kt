package org.balch.orpheus.djapp.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.ai.songref.SongPaste
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import org.balch.orpheus.djapp.ai.songref.pasteTitle
import kotlin.coroutines.CoroutineContext

/** What one change to the prompt field means. */
internal sealed interface DraftEdit {
    /** Plain text; [submit] when the user pressed Enter. */
    data class Text(val draft: String, val submit: Boolean) : DraftEdit

    /** A big paste to attach as a song reference; [draft] is the field without it. */
    data class Reference(val pasted: String, val draft: String) : DraftEdit
}

private const val HUGE_PASTE_CHARS = 2_000
private const val BIG_MULTILINE_CHARS = 200
private const val BIG_MULTILINE_LINES = 4
private val NEWLINE_RUN = Regex("""\s*\n\s*""")

/** Classifies the segment [new] inserted into [old] (common prefix and suffix trimmed off). */
internal fun classifyDraftEdit(old: String, new: String): DraftEdit {
    val maxCommon = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < maxCommon && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < maxCommon - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val inserted = new.substring(prefix, new.length - suffix)
    // Only the inserted text changes, so lines added with Shift+Enter survive.
    fun around(mid: String) = new.substring(0, prefix) + mid + new.substring(new.length - suffix)

    // Enter, or an IME composite commit ending in one newline, submits.
    val enter = inserted.length < BIG_MULTILINE_CHARS && inserted.endsWith("\n") && inserted.count { it == '\n' } == 1
    if (enter) return DraftEdit.Text(around(inserted.dropLast(1)), submit = true)
    val multiline = '\n' in inserted
    val big = inserted.length >= HUGE_PASTE_CHARS ||
        (multiline && (inserted.length >= BIG_MULTILINE_CHARS || inserted.lines().size >= BIG_MULTILINE_LINES))
    if (big) return DraftEdit.Reference(inserted, old)
    if (multiline) return DraftEdit.Text(around(inserted.replace(NEWLINE_RUN, " ")), submit = false)
    return DraftEdit.Text(new, submit = false)
}

/** The message as written, naming the attached paste so the agent can call song_reference. */
internal fun agentPrompt(draft: String, reference: SongPasteRef?): String {
    if (reference == null) return draft
    val head = draft.ifBlank { "Make a Pulsar vibe inspired by the attached song reference." }
    val title = pasteTitle(reference.title)?.let { ", \"$it\"" }.orEmpty()
    return "$head\n\nThe user attached a song reference (pasteId=\"${reference.id}\", ${reference.lines} lines$title). " +
        "Call song_reference with pasteId=\"${reference.id}\" before building."
}

/**
 * Stores pastes on [context], so a multi-MB page never blocks the field. [DjAiUiState.parsing] holds the ticket of the
 * paste still parsing; a newer attach or a detach replaces it, and a paste lands only while its ticket is current.
 */
internal class ReferenceAttacher(
    private val store: SongPasteStore,
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val state: MutableStateFlow<DjAiUiState>,
    // Tests swap this to park a parse mid-flight.
    private val put: (String) -> SongPaste = store::put,
) {
    private var pending: Job? = null
    private var waiting: Job? = null
    private var tickets = 0L

    fun attach(text: String) {
        pending?.cancel()
        val ticket = ++tickets
        state.update { it.copy(parsing = ticket) }
        pending = scope.launch(context) {
            // Unless it lands as the chip, the paste leaves the store: superseded, detached or torn down mid-parse.
            var orphan: SongPaste? = null
            try {
                val paste = put(text).also { orphan = it }
                ensureActive()
                val ref = SongPasteRef(paste.id, paste.lines, paste.title)
                // The ticket is checked inside the write, so a detach racing it can never leave a stale chip.
                val before = state.getAndUpdate { if (it.parsing == ticket) it.copy(reference = ref, parsing = null) else it }
                if (before.parsing == ticket) { orphan = null; before.reference?.let { store.remove(it.id) } }
            } finally {
                orphan?.let { store.remove(it.id) }
                state.update { if (it.parsing == ticket) it.copy(parsing = null) else it }
            }
        }
    }

    /** Runs [send] now, or once the paste still parsing lands, so an early Enter carries its pasteId; repeats while waiting are dropped. */
    fun whenAttached(send: () -> Unit) {
        if (waiting?.isActive == true) return
        if (state.value.parsing == null) { send(); return }
        waiting = scope.launch {
            state.first { it.parsing == null }
            send()
        }
    }

    fun detach() {
        pending?.cancel()
        pending = null
        state.getAndUpdate { it.copy(reference = null, parsing = null) }.reference?.let { store.remove(it.id) }
    }
}
