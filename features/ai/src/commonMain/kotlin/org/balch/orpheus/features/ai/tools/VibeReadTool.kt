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

@LLMDescription("Arguments for reading a Pulsar vibe as JSON: 'current' to tweak what plays, or a shipped vibe for reference.")
@Serializable
data class VibeReadArgs(
    @property:LLMDescription("The literal 'current' for the vibe playing now, or a shipped vibe's display name (reference only). Use a name from a previous result's availableNames.")
    val name: String,
)

@LLMDescription("A Pulsar vibe serialized as JSON, plus the list of shipped vibe names.")
@Serializable
data class VibeReadResult(
    @property:LLMDescription("True if a vibe was found and returned.")
    val success: Boolean,
    @property:LLMDescription("The vibe as JSON. For 'current', edit it and pass it to pulsar_apply_vibe. Empty when success is false.")
    val vibeJson: String,
    @property:LLMDescription("All shipped vibe names you can read for reference.")
    val availableNames: List<String>,
    @property:LLMDescription("Human-readable status or error.")
    val message: String,
    @property:LLMDescription("Set when a shipped vibe was read: how you may use it.")
    val note: String? = null,
)

/** The [VibeReadResult.note] on every shipped vibe read, so the model never starts from one. */
internal const val REFERENCE_ONLY_NOTE =
    "Reference only: borrow ideas, never start from this vibe. Start new vibes with pulsar_cast_vibe."

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
    return VibeReadResult(true, json.encodeToString(match), vibeNames, "Reference: ${match.name}", REFERENCE_ONLY_NOTE)
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

    // Encode shipped vibes with the SAME config the apply path decodes with, so their class
    // discriminator can never drift from what pulsar_apply_vibe expects. (coerceInputValues and the
    // polymorphic fallbacks on vibeApplyJson are decode-only — they don't change encode output.)
    private val json = vibeApplyJson

    override val tool by lazy {
        object : Tool<VibeReadArgs, VibeReadResult>(
            argsType = typeToken<VibeReadArgs>(),
            resultType = typeToken<VibeReadResult>(),
            name = "pulsar_get_vibe",
            description = """
                Read a Pulsar beat-machine vibe as JSON.
                Pass 'current' to tweak the vibe playing now: change only the fields you need and send
                it to pulsar_apply_vibe.
                Pass a shipped vibe's name for reference only: borrow ideas (how it builds a singer,
                a groove, a section), never start from this vibe. Start new vibes with pulsar_cast_vibe.
                For DX/DX2/DX3, 'harmonics' selects a 32-patch bank (set it deliberately); it is not a
                tone knob.
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
