package org.balch.orpheus.djapp.ai.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.djapp.ai.collab.CollabMixer

@Serializable
data class MixClearChangesArgs(
    @property:LLMDescription("The vibe's name. Default: the playing vibe.")
    val vibe: String? = null,
)

@Serializable
data class MixClearChangesResult(val ok: Boolean, val message: String)

/**
 * Clears a vibe's saved mix change set once its rows are in the vibe's source. Served by [OrphicMcpServer] only,
 * so the in-app agent cannot drop what the user has not captured.
 */
class MixClearChangesTool(private val collabMixer: () -> CollabMixer) : ToolProvider {
    override val tool: Tool<MixClearChangesArgs, MixClearChangesResult> by lazy {
        object : Tool<MixClearChangesArgs, MixClearChangesResult>(
            argsType = typeToken<MixClearChangesArgs>(),
            resultType = typeToken<MixClearChangesResult>(),
            name = "mix_clear_changes",
            description = "Clear a vibe's saved mix change set after its rows were written into the vibe's source or " +
                "ai-vibes JSON. Defaults to the playing vibe; ignores pins.",
        ) {
            override suspend fun execute(args: MixClearChangesArgs): MixClearChangesResult {
                val name = args.vibe?.trim()?.takeIf { it.isNotEmpty() }
                return if (collabMixer().clearChanges(name)) {
                    MixClearChangesResult(true, "Cleared the mix changes for ${name?.let { "\"$it\"" } ?: "the playing vibe"}.")
                } else {
                    MixClearChangesResult(false, "A vibe is loading; try again once it plays.")
                }
            }
        }
    }
}
