package org.balch.orpheus.djapp.ai.ideas

import kotlin.math.roundToInt
import org.balch.orpheus.djapp.ai.DjAiFeedItem
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.effective
import org.balch.orpheus.features.pulsar.models.Vibe

private const val RECENT_CHAT = 6
private const val RECENT_MOVES = 8
private const val CHAT_CHARS = 200

/** Makes the ideas strip's cards; an empty list means "keep what the strip shows". */
fun interface IdeasGenerator {
    suspend fun ideas(input: IdeasInput): List<Idea>
}

/** What an ideas call sees: the vibe, the mix, the user's latest moves and the last few chat lines. */
data class IdeasInput(
    val mixerOpen: Boolean,
    val vibeSummary: String,
    val mixSnapshot: String,
    val recentMoves: List<String>,
    val recentChat: List<String>,
)

/** The last few "You:" and "Agent:" lines of [feed], each cut short. */
internal fun recentChat(feed: List<DjAiFeedItem>): List<String> =
    feed.mapNotNull {
        when (it) {
            is DjAiFeedItem.Request -> "You: ${it.text.take(CHAT_CHARS)}"
            is DjAiFeedItem.Reply -> "Agent: ${it.text.take(CHAT_CHARS)}"
            else -> null
        }
    }.takeLast(RECENT_CHAT)

/** The user's last few mixer moves in [feed], as the Mixer labels them. */
internal fun recentMoves(feed: List<DjAiFeedItem>): List<String> =
    feed.filterIsInstance<DjAiFeedItem.Move>()
        .filter { it.origin == EditOrigin.USER }
        .takeLast(RECENT_MOVES)
        .map { "${it.label}: ${it.before} -> ${it.after}" }

/** One line: name, tempo, key, and where the arrangement is. */
internal fun vibeSummary(vibe: Vibe, section: Int): String = buildString {
    append(vibe.name).append(", ").append(vibe.bpm.roundToInt()).append(" BPM, ")
    append(vibe.rootNote.name).append(' ').append(vibe.scaleType.name.lowercase().replace('_', ' '))
    val sections = vibe.arrangement?.sections.orEmpty()
    if (sections.isEmpty()) return@buildString
    sections.getOrNull(section)?.let { append(". Playing ").append(it.name).append(" (${section + 1} of ${sections.size})") }
    append(". Sections: ").append(sections.joinToString(", ") { it.name })
}

/** One line per track as heard in [section], then the macro knobs. */
internal fun mixSnapshot(
    vibe: Vibe,
    section: Int,
    layers: BenchLayers,
    energy: Float,
    muted: Set<Int>,
    soloed: Set<Int>,
    macros: Map<String, Float>,
): String = buildString {
    for (t in 0 until minOf(BenchMeters.CHANNEL_NAMES.size, vibe.tracks.size)) {
        val e = layers.effective(vibe, section, t, energy)
        append(BenchMeters.CHANNEL_NAMES[t]).append(' ').append(e.levelDb.r1()).append(" dB, pan ").append(e.pan.r2())
        append(", reverb ").append(e.reverbSend.r2()).append(", delay ").append(e.delaySend.r2())
        if (t in muted) append(", muted")
        if (t in soloed) append(", solo")
        append('\n')
    }
    append(macros.entries.joinToString(", ") { (m, v) -> "$m ${v.r2()}" })
}

private fun Float.r1(): Float = (this * 10f).roundToInt() / 10f

private fun Float.r2(): Float = (this * 100f).roundToInt() / 100f
