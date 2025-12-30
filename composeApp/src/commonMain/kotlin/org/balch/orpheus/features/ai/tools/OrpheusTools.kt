package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Provides all Orpheus AI agent tools.
 */
@SingleIn(AppScope::class)
class OrpheusTools @Inject constructor(
    synthControlTool: SynthControlTool,
    voiceTriggerTool: VoiceTriggerTool,
    replExecuteTool: ReplExecuteTool,
    panelExpandTool: PanelExpandTool,
    timeTools: TimeTools,
) {
    val tools: List<Tool<*, *>> = listOf(
        synthControlTool,
        voiceTriggerTool,
        replExecuteTool,
        panelExpandTool,
        timeTools.currentDatetimeTool,
    )
}
