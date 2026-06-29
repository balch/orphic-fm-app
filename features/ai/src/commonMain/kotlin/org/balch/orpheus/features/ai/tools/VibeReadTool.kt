package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Vibe

@LLMDescription("Arguments for reading an existing Pulsar vibe as JSON, to use as a template to edit.")
@Serializable
data class VibeReadArgs(
    @property:LLMDescription("Vibe display name to read, or the literal 'current' for the vibe playing now. Use a name from a previous result's availableNames.")
    val name: String,
)

@LLMDescription("A Pulsar vibe serialized as JSON, plus the list of vibe names available as templates.")
@Serializable
data class VibeReadResult(
    @property:LLMDescription("True if a vibe was found and returned.")
    val success: Boolean,
    @property:LLMDescription("The vibe as JSON. Edit this and pass it to pulsar_apply_vibe. Empty when success is false.")
    val vibeJson: String,
    @property:LLMDescription("All vibe names you can read as templates.")
    val availableNames: List<String>,
    @property:LLMDescription("Human-readable status or error.")
    val message: String,
)

/** Pure resolution logic — no PulsarFeature needed, so it is unit-testable. */
internal fun resolveVibeJson(
    json: Json,
    name: String,
    current: Vibe,
    vibeList: List<Vibe>,
    vibeNames: List<String>,
): VibeReadResult {
    if (name.equals("current", ignoreCase = true)) {
        return VibeReadResult(true, json.encodeToString(current), vibeNames, "Current vibe: ${current.name}")
    }
    val match = vibeList.firstOrNull { it.name == name }
        ?: return VibeReadResult(
            success = false,
            vibeJson = "",
            availableNames = vibeNames,
            message = "No vibe named '$name'. Available: ${vibeNames.joinToString(", ")}",
        )
    return VibeReadResult(true, json.encodeToString(match), vibeNames, "Template: ${match.name}")
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeReadTool(
    // Lazy provider, not a direct PulsarFeature: injecting the feature eagerly here pulls
    // PulsarViewModel into the OrpheusAgentConfig tool-set construction, closing a DI cycle
    // (Pulsar -> PlaybackController -> AiOptionsViewModel -> OrpheusAgent -> tools -> Pulsar).
    // Mirrors PulsarSongEnding/PulsarMetadataProducer/PulsarVibePicker which take the same provider.
    private val pulsarFeatureProvider: () -> PulsarFeature,
) : ToolProvider {

    // Encode templates with the SAME config the apply path decodes with, so the template's class
    // discriminator can never drift from what pulsar_apply_vibe expects. (coerceInputValues and the
    // polymorphic fallbacks on vibeApplyJson are decode-only — they don't change encode output.)
    private val json = vibeApplyJson

    override val tool by lazy {
        object : Tool<VibeReadArgs, VibeReadResult>(
            argsType = typeToken<VibeReadArgs>(),
            resultType = typeToken<VibeReadResult>(),
            name = "pulsar_get_vibe",
            description = """
                Read an existing Pulsar beat-machine vibe as JSON so you can edit it into a new vibe.
                Call pulsar_vibe_guide first for what the fields mean and which template to start from.
                Pass a known-good vibe name (or 'current' for what is playing) to get
                a complete, valid template, then change only the fields you need and send it to
                pulsar_apply_vibe. Never write a vibe from scratch — edit a template.
                Keep each track's engine in its role family; for DX/DX2/DX3, 'harmonics' selects a
                32-patch bank (set it deliberately), it is not a tone knob.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeReadArgs): VibeReadResult {
                val pulsar = pulsarFeatureProvider()
                return resolveVibeJson(json, args.name, pulsar.vibeFlow.value, pulsar.vibeList, pulsar.vibeNames)
            }
        }
    }

    private val log = logging("VibeReadTool")
}
