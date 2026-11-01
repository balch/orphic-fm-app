package org.balch.orpheus.djapp.ai.collab

/**
 * The lines that open each agent turn: whether the Mixer is open, which strips are pinned, and the user's
 * moves since the agent's last turn, oldest first. The moves block is left out when there are none.
 */
fun turnContext(
    moves: List<MixMove>,
    mixerOpen: Boolean,
    pinnedNames: List<String>,
    labels: (MixControl) -> String,
): String = buildString {
    append("[Mixer: ").append(if (mixerOpen) "open" else "closed")
    append(". Pinned: ").append(pinnedNames.ifEmpty { listOf("none") }.joinToString(", ")).append(']')
    if (moves.isEmpty()) return@buildString
    append("\n[User moves since your last turn, oldest first]")
    for (m in moves.sortedBy { it.atMs }) {
        append("\n- ")
        if (m.origin == EditOrigin.MCP) append("(Claude Code) ")
        append(labels(m.control)).append(": ").append(m.before).append(" -> ").append(m.after)
    }
}
