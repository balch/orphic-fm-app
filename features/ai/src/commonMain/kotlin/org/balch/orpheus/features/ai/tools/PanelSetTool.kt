package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.ui.panels.PanelSetRegistry
import org.balch.orpheus.core.panels.panelSet
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.features.ai.PanelExpansionEventBus

@Serializable
data class PanelSetArgs(
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
data class PanelSetResult(
    val success: Boolean,
    val message: String,
)

/**
 * AI tool for managing panel set layouts.
 *
 * Actions:
 * - list: Show all available panel sets with their panels
 * - apply: Activate an existing panel set by name
 * - create: Build a new panel set from panel IDs, add to registry, and activate it
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class PanelSetTool @Inject constructor(
    private val registry: PanelSetRegistry,
    private val eventBus: PanelExpansionEventBus,
) : ToolProvider {

    override val tool by lazy {
        object : Tool<PanelSetArgs, PanelSetResult>(
            argsSerializer = PanelSetArgs.serializer(),
            resultSerializer = PanelSetResult.serializer(),
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
            override suspend fun execute(args: PanelSetArgs): PanelSetResult {
                return executeInternal(args)
            }
        }
    }

    private val log = logging("PanelSetTool")

    private suspend fun executeInternal(args: PanelSetArgs): PanelSetResult {
        return when (args.action.lowercase()) {
            "list" -> executeList()
            "apply" -> executeApply(args)
            "create" -> executeCreate(args)
            else -> PanelSetResult(false, "Unknown action: ${args.action}. Use 'list', 'apply', or 'create'.")
        }
    }

    private fun executeList(): PanelSetResult {
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
        return PanelSetResult(true, "Panel sets:\n$summary")
    }

    private suspend fun executeApply(args: PanelSetArgs): PanelSetResult {
        val name = args.name ?: return PanelSetResult(false, "Name required for 'apply' action")
        val set = registry.getByName(name)
            ?: return PanelSetResult(false, "Panel set not found: $name")
        registry.activate(name)
        eventBus.applyPanelSet(set)
        log.debug { "PanelSetTool: applied '$name'" }
        return PanelSetResult(true, "Activated panel set: ${set.name}")
    }

    private suspend fun executeCreate(args: PanelSetArgs): PanelSetResult {
        val name = args.name ?: return PanelSetResult(false, "Name required for 'create' action")
        val expanded = args.expandedPanels ?: emptyList()
        val collapsed = args.collapsedPanels ?: emptyList()

        if (expanded.isEmpty() && collapsed.isEmpty()) {
            return PanelSetResult(false, "At least one panel ID required for 'create'")
        }

        val newSet = panelSet(name) {
            expanded.forEach { id ->
                expand(PanelId(id))
            }
            collapsed.forEach { id ->
                collapse(PanelId(id))
            }
        }

        registry.add(newSet)
        registry.activate(name)
        eventBus.applyPanelSet(newSet)
        log.debug { "PanelSetTool: created and activated '$name' with ${newSet.panels.size} panels" }
        return PanelSetResult(true, "Created and activated panel set: $name (${newSet.panels.size} panels)")
    }
}
