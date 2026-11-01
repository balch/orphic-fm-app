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
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.VibeEditResult
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.bench.isMixerPath
import org.balch.orpheus.features.pulsar.bench.patched
import org.balch.orpheus.features.pulsar.bench.vibeTreeJson

@LLMDescription("One field of the playing vibe to change.")
@Serializable
data class VibeEdit(
    @property:LLMDescription("Path into the vibe JSON, e.g. bpm, scaleType, tracks[2].engineEdm.harmonics.")
    val path: String,
    @property:LLMDescription(
        "The new value, JSON-encoded as text, in the shape pulsar_get_vibe('current') shows: 96, \"MAJOR\", true, " +
            "or an object or array as text. Always a string, even for numbers, e.g. \"96\".",
    )
    val value: String,
)

@LLMDescription("Arguments for editing the playing vibe.")
@Serializable
data class VibeEditArgs(
    @property:LLMDescription("The fields to change for one musical idea, which may touch several fields. Make one change at a time.")
    val edits: List<VibeEdit>,
)

@LLMDescription("What pulsar_edit_vibe did. ok=false: message says why; fix the edit or ask the user, as it says.")
@Serializable
data class VibeEditToolResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("History rows the edit changed.")
    val rows: Int = 0,
    @property:LLMDescription("True when this turn's edits are used up: reply to the user now.")
    val budgetSpent: Boolean = false,
)

/** What a recorded vibe edit tells the agent; [ignored] are mixer paths the edit carried but the Mixer owns. */
internal fun vibeEditedMessage(name: String, rows: Int, ignored: List<String>, restarted: Boolean = true): String {
    val head = when {
        rows == 0 -> "No field changed on '$name'; the song kept playing."
        restarted -> "Recorded as $rows History rows on '$name'; the song restarted."
        else -> "Recorded as $rows History rows on '$name'; the song played on with the change."
    }
    if (ignored.isEmpty()) return head
    val more = if (ignored.size > IGNORED_SHOWN) " and ${ignored.size - IGNORED_SHOWN} more" else ""
    return "$head Left alone, because the Mixer owns them: ${ignored.take(IGNORED_SHOWN).joinToString()}$more. Use mix_set for those."
}

private const val IGNORED_SHOWN = 5
private const val NEW_VIBE = "Sections and tracks are fixed in an edit. Ask the user whether to make a brand new vibe in this " +
    "direction (pulsar_cast_vibe, then pulsar_apply_vibe under a new original name)."

/** [reason] with the brand-new-vibe pointer added when it is a structure refusal. */
internal fun withNewVibePointer(reason: String, structure: Boolean): String =
    if (structure) "$reason $NEW_VIBE" else reason

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeEditTool(
    private val turnBudget: TurnBudget,
    private val collabMixer: CollabMixer,
) : ToolProvider {
    override val tool: Tool<VibeEditArgs, VibeEditToolResult> by lazy {
        object : Tool<VibeEditArgs, VibeEditToolResult>(
            argsType = typeToken<VibeEditArgs>(),
            resultType = typeToken<VibeEditToolResult>(),
            name = "pulsar_edit_vibe",
            description = """
                Change fields of the vibe that plays now, keeping the rest. Each changed field becomes a row in the
                Mixer's History, on top of the vibe's file, so the user can see it, toggle it, roll back and capture it.
                The song restarts to play the change, except when only engines, or their harmonics, timbre, morph or
                pins, or a track's slotLock, change: those play on live, a new engine from its track's next note.
                slotLock (AUTO, EDM or SPACE) holds a track on one engine whatever the energy. Use this for tweaks;
                use pulsar_apply_vibe only for a new vibe.
                Loop: make one change, tell the user what changed in plain words, then wait for their reaction.
                Path syntax: dotted names with [index] for lists. Examples:
                  path "bpm", value "96"
                  path "tracks[2].engineEdm.harmonics", value "0.4"
                  path "scaleType", value "\"MAJOR\""
                Each value is JSON text, so a string value carries its own quotes inside the text.
                Levels, pans, sends, the energy/complexity/mood/space macros, section macros and delay are the Mixer's:
                use mix_set for those (this tool refuses them). Sections and tracks are fixed: no adding, removing or
                renaming. For a new shape of song, ask the user about a brand new vibe instead.
                An edit that decodes spends one of the ${TurnBudget.MAX_APPLIES} applies a turn allows, so
                a refused or no-op edit spends one too.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeEditArgs): VibeEditToolResult = guarded({ VibeEditToolResult(false, it) }) {
                if (args.edits.isEmpty()) return@guarded VibeEditToolResult(false, "No edits given.")
                args.edits.firstOrNull { isMixerPath(it.path) }?.let {
                    return@guarded VibeEditToolResult(false, "'${it.path}' is the Mixer's: change it with mix_set, not pulsar_edit_vibe.")
                }
                val current = collabMixer.effectiveVibe()
                    ?: return@guarded VibeEditToolResult(false, CollabMixer.NOT_PLAYING)
                val parsed = try {
                    // koog cannot put a JsonElement in a tool schema, so each value arrives as JSON text.
                    args.edits.map { it.path to vibeTreeJson.parseToJsonElement(it.value) }.also { current.patched(it) }
                } catch (e: Exception) {
                    // Like a rejected apply: fixable, but only a few times a turn.
                    return@guarded rejected(e.message)
                }
                if (!turnBudget.allowApply()) return@guarded VibeEditToolResult(false, TurnBudget.SPENT, budgetSpent = true)
                // The mixer patches again under its lock, so rows above a heard point survive.
                when (val r = collabMixer.editVibe(collabMixer.toolOrigin(), parsed)) {
                    is VibeEditResult.Applied -> {
                        val msg = vibeEditedMessage(current.name, r.rows, r.ignoredMixerPaths, r.restarted)
                        VibeEditToolResult(true, if (r.rows == 0) msg else "Changed ${args.edits.joinToString { it.path }}. $msg", r.rows)
                    }
                    is VibeEditResult.Refused -> VibeEditToolResult(false, withNewVibePointer(r.reason, r.structure))
                    is VibeEditResult.Invalid -> VibeEditToolResult(false, "Could not edit the vibe: ${r.message}")
                }
            }

            private suspend fun rejected(error: String?) = if (turnBudget.allowReject()) {
                VibeEditToolResult(false, "Could not edit the vibe: $error")
            } else {
                VibeEditToolResult(
                    false,
                    "Still rejected after ${TurnBudget.MAX_REJECTED_APPLIES} fixes this turn. Stop and tell the user " +
                        "what kept failing. Last error: $error",
                    budgetSpent = true,
                )
            }
        }
    }
}
