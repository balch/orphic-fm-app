package org.balch.orpheus.djapp.ai.songref

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

private const val MAX_TITLE = 80
private val WHITESPACE_RUN = Regex("""\s+""")

/** [raw] on one line without double quotes and at most [MAX_TITLE] chars, for the chip and the agent prompt; null when blank. */
internal fun pasteTitle(raw: String?): String? =
    raw?.replace("\"", "")?.replace(WHITESPACE_RUN, " ")?.trim()?.take(MAX_TITLE)?.trimEnd()?.takeIf { it.isNotEmpty() }

/** A song page the user attached in the chat, kept in memory for the agent to read by id. */
data class SongPaste(val id: String, val text: String, val lines: Int, val chars: Int, val title: String?)

/** The newest [KEEP] pastes, in memory only. */
@SingleIn(AppScope::class)
@Inject
class SongPasteStore {
    // The id counter and the list move together, so the newest paste always has the highest id.
    private class Held(val last: Int = 0, val pastes: List<SongPaste> = emptyList())

    private val held = MutableStateFlow(Held())

    fun put(text: String): SongPaste {
        val page = extractPageText(text)
        val title = pasteTitle(page.title)
            ?: pasteTitle(page.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.length < 60 })
        val after = held.updateAndGet { h ->
            val paste = SongPaste("paste-${h.last + 1}", text, page.text.lines().size, text.length, title)
            Held(h.last + 1, (h.pastes + paste).takeLast(KEEP))
        }
        return after.pastes.last()
    }

    fun get(id: String): SongPaste? = held.value.pastes.firstOrNull { it.id == id }

    fun ids(): List<String> = held.value.pastes.map { it.id }

    val latest: SongPaste? get() = held.value.pastes.lastOrNull()

    fun remove(id: String) = held.update { h -> Held(h.last, h.pastes.filterNot { it.id == id }) }

    private companion object {
        const val KEEP = 4
    }
}
