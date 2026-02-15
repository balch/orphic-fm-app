package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import kotlin.jvm.JvmSuppressWildcards

@Serializable
data class SynthControlArgs(
    @property:LLMDescription("Short key for the synth parameter (e.g. 'reverb_amount', 'voice_vibrato'). Use user_manual tool to discover available keys. Use 'BENDER' for pitch bend.")
    val controlId: String,

    @property:LLMDescription("""
        Value for the control parameter (0.0 to 1.0, except BENDER which uses -1.0 to +1.0).

        Special cases:
        - BENDER: -1=full down, 0=center, +1=full up
        - Engine selection (pair_engine keys): Integer engine ID (0, 5-17). NOT 0-1 range.
        - Tune keys: 0.0-1.0 where 0.5=A3 (220Hz). tuneValue = 0.5 + (semitones from A3 / 48.0)
    """)
    val value: Float
)

@Serializable
data class SynthControlResult(val success: Boolean, val message: String)

/**
 * Tool for controlling synth parameters via the SynthController.
 * Uses linear ramping for smooth transitions when AI changes values.
 *
 * The tool description is built dynamically from the injected [SynthFeature.SynthControl] set,
 * so adding a new feature module automatically makes its controls visible to the AI.
 *
 * Controls are resolved by short key (e.g. "reverb_amount", "voice_vibrato") derived from
 * the URI suffix + symbol. Full keys (e.g. "org.balch.orpheus.plugins.reverb:amount") also accepted.
 *
 * ## Tuning Voices to Musical Notes
 *
 * VOICE_TUNE uses 0.0-1.0 where 0.5 = A3 (220Hz).
 * To tune to other notes: tuneValue = 0.5 + (semitones from A3 / 48.0)
 * Common notes: C4=0.562, D4=0.604, E4=0.646, F4=0.667, G4=0.708, A4=0.750
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class SynthControlTool @Inject constructor(
    private val synthController: SynthController,
    features: @JvmSuppressWildcards Set<SynthFeature<*, *>>
) : Tool<SynthControlArgs, SynthControlResult>(
    argsSerializer = SynthControlArgs.serializer(),
    resultSerializer = SynthControlResult.serializer(),
    name = "synth_control",
    description = buildToolDescription(features)
) {
    private val controls = features.map { it.synthControl }

    /**
     * Pre-built lookup: short key → PluginControlId.
     * Short key format: `{uriSuffix}_{symbol}`, e.g. `reverb_amount`, `voice_vibrato`.
     * Also accepts the full key (e.g. `org.balch.orpheus.plugins.reverb:amount`) as fallback.
     */
    private val controlLookup: Map<String, PluginControlId> = buildMap {
        for (ctrl in controls) {
            for (key in ctrl.portControlKeys.keys) {
                val parsed = PluginControlId.parse(key) ?: continue
                put(key, parsed)                        // full key
                put(shortKey(parsed), parsed)            // short key
            }
        }
    }

    /** Resolve a controlId string to a PluginControlId. Accepts short key or full key. */
    private fun resolve(controlId: String): PluginControlId? = controlLookup[controlId]

    // Track current values for each control to enable smooth ramping
    private val currentValues = mutableMapOf<PluginControlId, Float>()

    // Ramp configuration: 500ms over ~25 steps = 20ms per step
    private val rampDurationMs = 500L
    private val rampSteps = 25

    override suspend fun execute(args: SynthControlArgs): SynthControlResult {
        // Special handling for BENDER — uses -1 to +1 range
        if (args.controlId.uppercase() == "BENDER") {
            return executeBend(args.value)
        }

        val targetId = resolve(args.controlId)
            ?: return SynthControlResult(
                success = false,
                message = "Unknown control: ${args.controlId}. Use user_manual tool to find valid symbols."
            )

        // Engine selection (pair_engine keys) — set immediately as integer, no ramping
        if (targetId.symbol.startsWith("pair_engine")) {
            val intValue = args.value.toInt()
            synthController.setPluginControl(
                id = targetId,
                value = PortValue.IntValue(intValue),
                origin = ControlEventOrigin.AI
            )
            return SynthControlResult(success = true, message = "Set ${shortKey(targetId)} to engine $intValue")
        }

        val normalizedValue = args.value.coerceIn(0f, 1f)

        // Get current value (or use 0.5 as default starting point)
        val startValue = currentValues[targetId] ?: 0.5f
        val stepDelayMs = rampDurationMs / rampSteps

        // Linear ramp from current to target
        for (step in 1..rampSteps) {
            val t = step.toFloat() / rampSteps
            val interpolatedValue = startValue + (normalizedValue - startValue) * t

            synthController.setPluginControl(
                id = targetId,
                value = PortValue.FloatValue(interpolatedValue),
                origin = ControlEventOrigin.AI
            )

            if (step < rampSteps) {
                delay(stepDelayMs)
            }
        }

        // Update tracked value
        currentValues[targetId] = normalizedValue

        return SynthControlResult(
            success = true,
            message = "Ramped ${shortKey(targetId)} to ${(normalizedValue * 100).toInt()}%"
        )
    }

    /**
     * Special handling for BENDER control.
     * Uses -1 to +1 range and the dedicated emitBendChange method.
     */
    private suspend fun executeBend(targetValue: Float): SynthControlResult {
        val normalizedValue = targetValue.coerceIn(-1f, 1f)

        val startValue = currentValues[BENDER_KEY] ?: 0f
        val stepDelayMs = rampDurationMs / rampSteps

        for (step in 1..rampSteps) {
            val t = step.toFloat() / rampSteps
            val interpolatedValue = startValue + (normalizedValue - startValue) * t
            synthController.emitBendChange(interpolatedValue)
            if (step < rampSteps) {
                delay(stepDelayMs)
            }
        }

        currentValues[BENDER_KEY] = normalizedValue

        val direction = when {
            normalizedValue > 0.1f -> "up"
            normalizedValue < -0.1f -> "down"
            else -> "center"
        }

        return SynthControlResult(
            success = true,
            message = "Bent pitch $direction to ${(normalizedValue * 100).toInt()}%"
        )
    }

    companion object {
        /** Synthetic key for the bender (not a real plugin port) */
        private val BENDER_KEY = PluginControlId("bender", "bend")

        /**
         * Derive a compact key from a PluginControlId: `{uriSuffix}_{symbol}`.
         * E.g. `org.balch.orpheus.plugins.reverb` + `amount` → `reverb_amount`.
         */
        private fun shortKey(id: PluginControlId): String {
            val uriSuffix = id.uri.substringAfterLast('.')
            return "${uriSuffix}_${id.symbol}"
        }

        private fun buildToolDescription(features: Set<SynthFeature<*, *>>): String {
            val sortedFeatures = features.sortedBy { it.synthControl.title }

            val sections = sortedFeatures.joinToString("\n\n") { feature ->
                val ctrl = feature.synthControl
                val keys = ctrl.portControlKeys.entries.joinToString("\n") { (fullKey, desc) ->
                    val parsed = PluginControlId.parse(fullKey)
                    val display = if (parsed != null) shortKey(parsed) else fullKey
                    "  $display: $desc"
                }
                "${ctrl.title} (${ctrl.panelId.id}):\n$keys"
            }

            val keyboardSection = sortedFeatures
                .filter { it.keyBindings.isNotEmpty() }
                .joinToString("\n") { feature ->
                    val bindings = feature.keyBindings.joinToString(", ") { binding ->
                        val prefix = if (binding.requiresShift) "Shift+" else ""
                        "$prefix${binding.label}: ${binding.description}"
                    }
                    "${feature.synthControl.title}: $bindings"
                }
                .let {
                    if (it.isNotEmpty()) {
                        "\n\nKEYBOARD SHORTCUTS (for user reference - explain these when users ask how to play):\n$it"
                    } else ""
                }

            return """
                Control synth parameters by short key (e.g. reverb_amount, voice_vibrato).
                Use user_manual tool for detailed docs.

                Values: 0.0-1.0 (except BENDER: -1.0 to +1.0, engine IDs: integers).

                Available controls:
                $sections

                VOICE TUNING: tune keys use 0.0-1.0 where 0.5=A3 (220Hz).
                tuneValue = 0.5 + (semitones from A3 / 48.0)$keyboardSection
            """.trimIndent()
        }
    }
}
