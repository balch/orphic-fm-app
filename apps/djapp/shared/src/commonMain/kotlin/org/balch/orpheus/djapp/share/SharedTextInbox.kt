package org.balch.orpheus.djapp.share

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * Text handed to the app from outside (a shared tab). The latest offer waits until the AI chat [take]s it;
 * [openRequests] counts offers so the UI can open the AI sheet even when the same text arrives twice.
 */
@SingleIn(AppScope::class)
@Inject
class SharedTextInbox {
    /** Identifies this instance; a count saved under another session is from a dead process. */
    val session: Long = Random.nextLong()

    private val _pending = MutableStateFlow<String?>(null)
    private val _openRequests = MutableStateFlow(0)

    val pending: StateFlow<String?> = _pending.asStateFlow()
    val openRequests: StateFlow<Int> = _openRequests.asStateFlow()

    /** Blank text is ignored; a newer offer replaces one not yet taken. */
    fun offer(text: String) {
        if (text.isBlank()) return
        _pending.value = text
        _openRequests.update { it + 1 }
    }

    /** Returns the waiting text and clears it, once. */
    fun take(): String? = _pending.getAndUpdate { null }
}

/** The big-paste rule for the chat field and for shares: 2,000 chars, or multi-line with 200 chars or 4 lines. */
fun looksLikeSongPaste(text: String): Boolean =
    text.length >= 2_000 || ('\n' in text && (text.length >= 200 || text.lines().size >= 4))

private val LONE_URL = Regex("""https?://\S+""")

/** The trimmed [text] when it is one http(s) link and nothing else, else null. */
fun sharedUrlOnly(text: String): String? = text.trim().takeIf { LONE_URL.matches(it) }

/** Puts a share's [subject] (a page or tab title) above [text], unless it is blank, already the first line, or [text] is a lone link. */
fun withShareSubject(text: String, subject: String?): String {
    val title = subject?.trim().orEmpty()
    if (title.isEmpty() || sharedUrlOnly(text) != null) return text
    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (title == firstLine) text else "$title\n$text"
}

/** Saved "session:count" marker for the last share the UI opened for. */
fun shareMarker(session: Long, count: Int): String = "$session:$count"

/** True when [count] is a share not yet handled; a [saved] marker from another session counts as 0. */
fun shouldOpenForShare(saved: String?, session: Long, count: Int): Boolean {
    val parts = saved?.split(':')
    val handled = if (parts?.size == 2 && parts[0].toLongOrNull() == session) parts[1].toIntOrNull() ?: 0 else 0
    return count > handled
}
