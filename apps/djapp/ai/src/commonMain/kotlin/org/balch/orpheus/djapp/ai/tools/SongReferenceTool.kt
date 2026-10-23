package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.djapp.ai.songref.SongFileReader
import org.balch.orpheus.djapp.ai.songref.SongPasteStore
import org.balch.orpheus.djapp.ai.songref.SongReference
import org.balch.orpheus.djapp.ai.songref.parseSongReference
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType

@LLMDescription("Arguments for song_reference. Give at most one of pasteId, text or path.")
@Serializable
data class SongReferenceArgs(
    @property:LLMDescription("A paste the user attached in the chat. Omit pasteId, text and path to use the latest paste.")
    val pasteId: String? = null,
    @property:LLMDescription("The page or sheet itself: HTML or plain tab or chord text.")
    val text: String? = null,
    @property:LLMDescription("A file on this machine (html, htm, txt, tab, crd, chopro, pro, text, md).")
    val path: String? = null,
    @property:LLMDescription("Compute degrees in this key instead of the guessed one. Give with scaleType.")
    val rootNote: RootNote? = null,
    @property:LLMDescription("Scale for degrees instead of the guessed one. Give with rootNote.")
    val scaleType: ScaleType? = null,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class SongReferenceTool(
    private val pastes: SongPasteStore,
    private val files: SongFileReader,
) : ToolProvider {
    override val tool: Tool<SongReferenceArgs, SongReference> by lazy {
        object : Tool<SongReferenceArgs, SongReference>(
            argsType = typeToken<SongReferenceArgs>(),
            resultType = typeToken<SongReference>(),
            name = "song_reference",
            description = """
                Read a song reference the user supplied (a tab page's HTML such as Ultimate Guitar's, or plain tab or
                chord text) and return its key, chords with scale degrees, the song's sections in order, phrase slots
                (one per lyric cue line) and single-note melodies as ready licks.
                Every lick is at most 16 beats (4 bars), the longest a vibe at stepCount 64 plays; play the parts of a
                longer line from consecutive pool slots in consecutive sections (lickIndex).
                Give at most one of pasteId (a paste the user attached in the chat; omit all three to use the latest),
                text, or path (a file on this machine).
                rootNote and scaleType compute degrees in that key instead of the guessed one.
                On failure ok is false and message says why; do not retry the same call.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: SongReferenceArgs): SongReference =
                guarded({ SongReference(false, it) }) {
                    val raw = resolve(args)
                    parseSongReference(raw, args.rootNote, args.scaleType)
                }
        }
    }

    private suspend fun resolve(args: SongReferenceArgs): String {
        val pasteId = args.pasteId?.takeIf { it.isNotBlank() }
        val text = args.text?.takeIf { it.isNotBlank() }
        val path = args.path?.takeIf { it.isNotBlank() }
        if (listOf(pasteId, text, path).count { it != null } > 1) {
            badArg("Give only one of pasteId, text or path.")
        }
        val raw = when {
            text != null -> text
            path != null -> files.read(path)
            pasteId != null -> pastes.get(pasteId)?.text ?: badArg(unknownPaste(pasteId))
            else -> pastes.latest?.text ?: badArg(
                "No song reference: ask the user to paste a tab or chord sheet into the chat, or pass text or path.",
            )
        }
        if (raw.length > MAX_CHARS) badArg("The reference is over $MAX_CHARS characters; paste a shorter page.")
        return raw
    }

    private fun unknownPaste(id: String): String {
        val known = pastes.ids()
        return "No paste \"$id\"." + if (known.isEmpty()) " No pastes are held." else " Known: ${known.joinToString()}."
    }

    private companion object {
        const val MAX_CHARS = 2_000_000
    }
}
