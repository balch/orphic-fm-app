package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.ui.panels.PanelSetRegistry
import org.balch.orpheus.core.panels.panelSet
import org.balch.orpheus.features.ai.PanelExpansionEventBus

/**
 * AI tool for managing panel set layouts.
 *
 * Actions:
 * - list: Show all available panel sets with their panels
 * - apply: Activate an existing panel set by name
 * - create: Build a new panel set from panel IDs, add to registry, and activate it
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class PanelSetTool @Inject constructor(
    private val registry: PanelSetRegistry,
    private val eventBus: PanelExpansionEventBus,
) : Tool<PanelSetTool.Args, PanelSetTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "panel_set",
    description = """
        Manage panel set layouts in the synthesizer UI.
        Actions:
        - "list": Show all available panel sets and their panels.
        - "apply": Activate an existing panel set by name.
        - "create": Create a new panel set from a list of panel IDs, then activate it.
        Available panel IDs: presets, midi, viz, evo, lfo, delay, reverb,
        distortion, resonator, code, ai, beats, drums, grains, looper,
        warps, flux, flux_triggers, speech, tweaks.
    """.trimIndent()
) {
    private val log = logging("PanelSetTool")

    @Serializable
    data class Args(
        @property:LLMDescription("Action: 'list', 'apply', or 'create'")
        val action: String,

        @property:LLMDescription("Name of the panel set (for 'apply' or 'create')")
        val name: String? = null,

        @property:LLMDescription("List of panel IDs to include (for 'create'). These will be expanded by default.")
        val expandedPanels: List<String>? = null,

        @property:LLMDescription("List of panel IDs to include as collapsed (for 'create')")
        val collapsedPanels: List<String>? = null,
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String,
    )

    override suspend fun execute(args: Args): Result {
        return when (args.action.lowercase()) {
            "list" -> executeList()
            "apply" -> executeApply(args)
            "create" -> executeCreate(args)
            else -> Result(false, "Unknown action: ${args.action}. Use 'list', 'apply', or 'create'.")
        }
    }

    private fun executeList(): Result {
        val sets = registry.allSets.value
        val active = registry.activePanelSet.value
        val summary = sets.joinToString("\n") { set ->
            val marker = if (set.name == active.name) " (active)" else ""
            val panels = set.panels.joinToString(", ") { cfg ->
                val state = if (cfg.expanded) "+" else "-"
                "$state${cfg.panelId}"
            }
            "  ${set.name}$marker: [$panels]"
        }
        log.debug { "PanelSetTool: list -> ${sets.size} sets" }
        return Result(true, "Panel sets:\n$summary")
    }

    private suspend fun executeApply(args: Args): Result {
        val name = args.name ?: return Result(false, "Name required for 'apply' action")
        val set = registry.getByName(name)
            ?: return Result(false, "Panel set not found: $name")
        registry.activate(name)
        eventBus.applyPanelSet(set)
        log.debug { "PanelSetTool: applied '$name'" }
        return Result(true, "Activated panel set: ${set.name}")
    }

    private suspend fun executeCreate(args: Args): Result {
        val name = args.name ?: return Result(false, "Name required for 'create' action")
        val expanded = args.expandedPanels ?: emptyList()
        val collapsed = args.collapsedPanels ?: emptyList()

        if (expanded.isEmpty() && collapsed.isEmpty()) {
            return Result(false, "At least one panel ID required for 'create'")
        }

        val newSet = panelSet(name) {
            expanded.forEach { id ->
                expand(org.balch.orpheus.core.PanelId(id))
            }
            collapsed.forEach { id ->
                collapse(org.balch.orpheus.core.PanelId(id))
            }
        }

        registry.add(newSet)
        registry.activate(name)
        eventBus.applyPanelSet(newSet)
        log.debug { "PanelSetTool: created and activated '$name' with ${newSet.panels.size} panels" }
        return Result(true, "Created and activated panel set: $name (${newSet.panels.size} panels)")
    }
}
