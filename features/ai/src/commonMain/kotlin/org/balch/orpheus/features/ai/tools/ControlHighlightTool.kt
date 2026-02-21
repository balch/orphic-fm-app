package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.features.FeatureCollection
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.features.ai.ControlHighlightEventBus

@Serializable
data class ControlHighlightArgs(
    @property:LLMDescription("List of control IDs to highlight, e.g. ['delay_feedback', 'delay_mix'].")
    val controlIds: List<String> = emptyList(),

    @property:LLMDescription("Set to true to clear all highlights. Defaults to false.")
    val clear: Boolean = false
)

@Serializable
data class ControlHighlightResult(
    val success: Boolean,
    val message: String
)

/**
 * AI tool for highlighting specific controls in the UI with a gold glow.
 *
 * Used during tutorials and explanations to visually point at knobs, toggles,
 * and buttons that the agent is describing.
 *
 * Resolves short keys (e.g. "flux_steps") to full PluginControlId keys
 * (e.g. "org.balch.orpheus.plugins.flux:steps") so they match the UI widget controlIds.
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class ControlHighlightTool @Inject constructor(
    private val controlHighlightEventBus: ControlHighlightEventBus,
    private val featureCollection: FeatureCollection
) : ToolProvider {

    override val tool by lazy {
        object : Tool<ControlHighlightArgs, ControlHighlightResult>(
            argsSerializer = ControlHighlightArgs.serializer(),
            resultSerializer = ControlHighlightResult.serializer(),
            name = "control_highlight",
            description = """
        Highlight specific controls in the UI with a pulsing gold glow.
        Use this to visually point at knobs, toggles, or buttons while explaining them.
        Pass controlIds to highlight, or set clear=true to remove all highlights.
    """.trimIndent()
        ) {
            override suspend fun execute(args: ControlHighlightArgs): ControlHighlightResult {
                return executeInternal(args)
            }
        }
    }

    private val log = logging("ControlHighlightTool")

    /** Short key → full PluginControlId.key lookup, matching SynthControlTool's format. */
    private val shortToFullKey: Map<String, String> by lazy {
        buildMap {
            for (feature in featureCollection.allFeatures) {
                for (fullKey in feature.synthControl.portControlKeys.keys) {
                    val parsed = PluginControlId.parse(fullKey) ?: continue
                    val uriSuffix = parsed.uri.substringAfterLast('.')
                    val shortKey = "${uriSuffix}_${parsed.symbol}"
                    put(shortKey, fullKey)
                }
            }
        }
    }

    /** Resolve a controlId: try short key first, fall back to the raw string. */
    private fun resolveToFullKey(controlId: String): String =
        shortToFullKey[controlId] ?: controlId

    private suspend fun executeInternal(args: ControlHighlightArgs): ControlHighlightResult {
        log.debug { "ControlHighlightTool: controlIds=${args.controlIds} clear=${args.clear}" }

        if (args.clear) {
            controlHighlightEventBus.clearHighlights()
            return ControlHighlightResult(success = true, message = "All highlights cleared.")
        }

        if (args.controlIds.isEmpty()) {
            return ControlHighlightResult(success = false, message = "No controlIds provided and clear=false.")
        }

        val resolvedIds = args.controlIds.map { resolveToFullKey(it) }.toSet()
        controlHighlightEventBus.highlight(resolvedIds)
        return ControlHighlightResult(
            success = true,
            message = "Highlighting ${resolvedIds.size} control(s): ${args.controlIds.joinToString(", ")}"
        )
    }
}
