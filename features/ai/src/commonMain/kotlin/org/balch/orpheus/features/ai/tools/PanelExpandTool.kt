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
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.core.PanelId

@Serializable
data class PanelExpandArgs(
    @property:LLMDescription("""
            The panel ID string to expand or collapse, e.g. "code", "speech", "delay".
        """)
    val panelId: String,

    @property:LLMDescription("True to expand the panel, false to collapse it. Defaults to true.")
    val expand: Boolean = true
)

@Serializable
data class PanelExpandResult(
    val success: Boolean,
    val message: String
)

/**
 * Tool for expanding or collapsing UI panels.
 *
 * This allows the AI to control panel visibility, for example:
 * - Expand the CODE panel before inserting REPL code
 * - Show the VIZ panel to highlight visual effects
 * - Focus on specific panels during tutorials
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class PanelExpandTool @Inject constructor(
    private val panelExpansionEventBus: PanelExpansionEventBus
) : ToolProvider {

    override val tool by lazy {
        object : Tool<PanelExpandArgs, PanelExpandResult>(
            argsSerializer = PanelExpandArgs.serializer(),
            resultSerializer = PanelExpandResult.serializer(),
            name = "panel_expand",
            description = """
        Expand or collapse UI panels in the synthesizer interface.
        Use this to show relevant panels before performing actions,
        like expanding the CODE panel before inserting REPL patterns.
        Available panels: presets, midi, viz, evo, lfo, delay, reverb,
        distortion, resonator, code, ai, beats, drums, grains, looper,
        warps, flux, flux_triggers, speech, tweaks.
    """.trimIndent()
        ) {
            override suspend fun execute(args: PanelExpandArgs): PanelExpandResult {
                return executeInternal(args)
            }
        }
    }

    private val log = logging("PanelExpandTool")

    private suspend fun executeInternal(args: PanelExpandArgs): PanelExpandResult {
        val panelId = PanelId(args.panelId)
        log.debug { "PanelExpandTool: ${panelId.id} expand=${args.expand}" }

        if (args.expand) {
            panelExpansionEventBus.expand(panelId)
        } else {
            panelExpansionEventBus.collapse(panelId)
        }

        val action = if (args.expand) "expanded" else "collapsed"
        log.debug { "PanelExpandTool: ${panelId.id} $action" }
        return PanelExpandResult(
            success = true,
            message = "${panelId.id} panel $action"
        )
    }
}
