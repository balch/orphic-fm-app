package org.balch.orpheus.djapp.ai

import com.diamondedge.logging.logging
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.ai.songref.SongPaste
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import org.balch.orpheus.djapp.ai.songref.pasteTitle
import org.balch.orpheus.djapp.share.SharedTextInbox
import org.balch.orpheus.djapp.share.looksLikeSongPaste
import org.balch.orpheus.djapp.share.sharedUrlOnly
import kotlin.coroutines.CoroutineContext

/** What one change to the prompt field means. */
internal sealed interface DraftEdit {
    /** Plain text; [submit] when the user pressed Enter. */
    data class Text(val draft: String, val submit: Boolean) : DraftEdit

    /** A big paste to attach as a song reference; [draft] is the field without it. */
    data class Reference(val pasted: String, val draft: String) : DraftEdit
}

// The enter check below treats an insert shorter than this as a keystroke, not a paste.
private const val BIG_MULTILINE_CHARS = 200
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
    if (looksLikeSongPaste(inserted)) return DraftEdit.Reference(inserted, old)
    if ('\n' in inserted) return DraftEdit.Text(around(inserted.replace(NEWLINE_RUN, " ")), submit = false)
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
    // Shares attach from a background dispatcher while the field attaches from the UI thread.
    private val lock = SynchronizedObject()
    private var pending: Job? = null
    private var waiting: Job? = null
    private var queuedShare: Job? = null
    private var tickets = 0L

    fun attach(text: String) {
        synchronized(lock) { queuedShare?.cancel(); attachLocked(text) }
    }

    private fun attachLocked(text: String) {
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
        val now = synchronized(lock) {
            if (waiting?.isActive == true) return
            val now = state.value.parsing == null
            if (!now) waiting = scope.launch {
                state.first { it.parsing == null }
                send()
            }
            now
        }
        if (now) send()
    }

    /**
     * Attaches a shared [text], but only after an Enter waiting on the paste still parsing has gone out with that
     * paste. A newer attach or a detach drops the queued share.
     */
    fun attachAfterPendingSend(text: String) {
        synchronized(lock) {
            queuedShare?.cancel()
            val enter = waiting?.takeIf { it.isActive }
            if (enter == null) { attachLocked(text); return }
            queuedShare = scope.launch {
                enter.join()
                // A newer attach may cancel this between join() and the lock; recheck inside it.
                synchronized(lock) { ensureActive(); attachLocked(text) }
            }
        }
    }

    fun detach() {
        synchronized(lock) {
            queuedShare?.cancel()
            pending?.cancel()
            pending = null
            state.getAndUpdate { it.copy(reference = null, parsing = null) }.reference?.let { store.remove(it.id) }
        }
    }
}

private val shareLog = logging("SharedText")

/**
 * Moves each text shared into the app into the chat without sending it: a lone link joins the draft, anything else
 * becomes the song-reference chip once a waiting Enter has gone out.
 */
internal suspend fun drainSharedText(inbox: SharedTextInbox, state: MutableStateFlow<DjAiUiState>, references: ReferenceAttacher) {
    inbox.pending.filterNotNull().collect {
        val text = inbox.take() ?: return@collect
        val url = sharedUrlOnly(text)
        if (url != null) {
            state.update { s -> s.copy(promptDraft = if (s.promptDraft.isBlank()) url else "${s.promptDraft.trimEnd()} $url") }
            shareLog.debug { "shared link added to the draft" }
        } else {
            references.attachAfterPendingSend(text)
            shareLog.debug { "shared text attached (${text.length} chars)" }
        }
    }
}
