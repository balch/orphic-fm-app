package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.ai.UserManualRegistry

/**
 * AI tool for looking up feature documentation.
 *
 * Supports three modes:
 * - panelId: Look up a specific panel's full documentation
 * - controlId: Look up what a specific control does and which panel it belongs to
 * - query: Search across all manuals for matching content
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class UserManualTool @Inject constructor(
    private val userManualRegistry: UserManualRegistry
) : Tool<UserManualTool.Args, UserManualTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "user_manual",
    description = """
        Look up documentation for synthesizer features and controls.
        Use panelId to get full panel docs, controlId to look up a specific knob/button,
        or query to search across all documentation.
        Available panels: ${""/* filled dynamically in execute */}
    """.trimIndent()
) {
    private val log = logging("UserManualTool")

    @Serializable
    data class Args(
        @property:LLMDescription("Panel ID to look up, e.g. 'delay', 'reverb', 'resonator', 'flux'.")
        val panelId: String? = null,

        @property:LLMDescription("Control ID to look up, e.g. 'delay_feedback', 'resonator_mode'.")
        val controlId: String? = null,

        @property:LLMDescription("Free-text search query to find relevant documentation.")
        val query: String? = null
    )

    @Serializable
    data class Result(
        val found: Boolean,
        val content: String,
        val availablePanels: List<String>
    )

    override suspend fun execute(args: Args): Result {
        log.debug { "UserManualTool: panelId=${args.panelId} controlId=${args.controlId} query=${args.query}" }

        val available = userManualRegistry.availablePanels

        // Look up by controlId
        if (args.controlId != null) {
            val description = userManualRegistry.describeControl(args.controlId)
            val panelId = userManualRegistry.findPanelForControl(args.controlId)
            if (description != null && panelId != null) {
                val manual = userManualRegistry.lookup(panelId)
                return Result(
                    found = true,
                    content = "**${args.controlId}** (${panelId.id} panel): $description\n\n" +
                        "Full panel documentation:\n${manual?.markdown ?: ""}",
                    availablePanels = available
                )
            }
            return Result(
                found = false,
                content = "Control '${args.controlId}' not found in any documented panel.",
                availablePanels = available
            )
        }

        // Look up by panelId
        if (args.panelId != null) {
            val manual = userManualRegistry.lookup(args.panelId)
            if (manual != null) {
                val controlList = if (manual.portControlKeys.isNotEmpty()) {
                    "\n\n**Controls:**\n" + manual.portControlKeys.entries.joinToString("\n") { (id, desc) ->
                        "- `$id`: $desc"
                    }
                } else ""
                val keyboardSection = userManualRegistry.formatKeyboardBindings(manual)
                return Result(
                    found = true,
                    content = "# ${manual.title}\n\n${manual.markdown}$controlList$keyboardSection",
                    availablePanels = available
                )
            }
            return Result(
                found = false,
                content = "No documentation found for panel '${args.panelId}'.",
                availablePanels = available
            )
        }

        // Search by query
        if (args.query != null) {
            val results = userManualRegistry.search(args.query)
            if (results.isNotEmpty()) {
                val summaries = results.joinToString("\n\n---\n\n") { manual ->
                    "## ${manual.title} (${manual.panelId.id})\n${manual.markdown}"
                }
                return Result(
                    found = true,
                    content = summaries,
                    availablePanels = available
                )
            }
            return Result(
                found = false,
                content = "No documentation matched query '${args.query}'.",
                availablePanels = available
            )
        }

        // No args — return list of available panels
        return Result(
            found = true,
            content = "Available documented panels: ${available.joinToString(", ")}",
            availablePanels = available
        )
    }
}
