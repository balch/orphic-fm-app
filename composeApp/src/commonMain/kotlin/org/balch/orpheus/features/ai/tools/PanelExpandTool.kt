package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.features.ai.PanelId

/**
 * Tool for expanding or collapsing UI panels.
 * 
 * This allows the AI to control panel visibility, for example:
 * - Expand the CODE panel before inserting REPL code
 * - Show the VIZ panel to highlight visual effects
 * - Focus on specific panels during tutorials
 */
@SingleIn(AppScope::class)
class PanelExpandTool @Inject constructor(
    private val panelExpansionEventBus: PanelExpansionEventBus
) : Tool<PanelExpandTool.Args, PanelExpandTool.Result>(

    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "panel_expand",
    description = """
        Expand or collapse UI panels in the synthesizer interface.
        Use this to show relevant panels before performing actions,
        like expanding the CODE panel before inserting REPL patterns.
    """.trimIndent()
) {
    private val log = logging("PanelExpandTool")

    @Serializable
    data class Args(
        @property:LLMDescription("""
            The panel to expand or collapse. Valid values:
            - "CODE" - Live Code REPL editor
            - "PRESETS" - Preset selector
            - "MIDI" - MIDI settings
            - "STEREO" - Stereo imaging controls
            - "VIZ" - Visualization settings
            - "LFO" - Hyper LFO modulation
            - "DELAY" - Mod Delay effects
            - "DISTORTION" - Distortion effects
            - "AI" - AI options panel
        """)
        val panelId: String,
        
        @property:LLMDescription("True to expand the panel, false to collapse it. Defaults to true.")
        val expand: Boolean = true
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String
    )

    override suspend fun execute(args: Args): Result {
        log.info { "PanelExpandTool: ${args.panelId} expand=${args.expand}" }
        
        val panelId = try {
            PanelId.valueOf(args.panelId.uppercase())
        } catch (e: IllegalArgumentException) {
            log.warn { "PanelExpandTool: Unknown panel ${args.panelId}" }
            return Result(
                success = false,
                message = "Unknown panel: ${args.panelId}. Valid panels: ${PanelId.entries.joinToString { it.name }}"
            )
        }
        
        if (args.expand) {
            panelExpansionEventBus.expand(panelId)
        } else {
            panelExpansionEventBus.collapse(panelId)
        }
        
        val action = if (args.expand) "expanded" else "collapsed"
        log.info { "PanelExpandTool: ${panelId.displayName} $action" }
        return Result(
            success = true,
            message = "${panelId.displayName} panel $action"
        )
    }
}
