package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.ai.ControlHighlightEventBus

/**
 * AI tool for highlighting specific controls in the UI with a gold glow.
 *
 * Used during tutorials and explanations to visually point at knobs, toggles,
 * and buttons that the agent is describing.
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class ControlHighlightTool @Inject constructor(
    private val controlHighlightEventBus: ControlHighlightEventBus
) : Tool<ControlHighlightTool.Args, ControlHighlightTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "control_highlight",
    description = """
        Highlight specific controls in the UI with a pulsing gold glow.
        Use this to visually point at knobs, toggles, or buttons while explaining them.
        Pass controlIds to highlight, or set clear=true to remove all highlights.
    """.trimIndent()
) {
    private val log = logging("ControlHighlightTool")

    @Serializable
    data class Args(
        @property:LLMDescription("List of control IDs to highlight, e.g. ['delay_feedback', 'delay_mix'].")
        val controlIds: List<String> = emptyList(),

        @property:LLMDescription("Set to true to clear all highlights. Defaults to false.")
        val clear: Boolean = false
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String
    )

    override suspend fun execute(args: Args): Result {
        log.debug { "ControlHighlightTool: controlIds=${args.controlIds} clear=${args.clear}" }

        if (args.clear) {
            controlHighlightEventBus.clearHighlights()
            return Result(success = true, message = "All highlights cleared.")
        }

        if (args.controlIds.isEmpty()) {
            return Result(success = false, message = "No controlIds provided and clear=false.")
        }

        controlHighlightEventBus.highlight(args.controlIds.toSet())
        return Result(
            success = true,
            message = "Highlighting ${args.controlIds.size} control(s): ${args.controlIds.joinToString(", ")}"
        )
    }
}
