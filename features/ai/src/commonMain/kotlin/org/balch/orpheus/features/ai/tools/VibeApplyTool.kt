package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.anonmalies.Anomaly
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly

/**
 * JSON config for decoding agent-emitted vibes.
 *
 * `coerceInputValues` makes an unknown *enum* value on a field WITH a default (e.g. the agent
 * writing a title into the `album` enum) fall back to that default instead of throwing.
 *
 * The `polymorphicDefaultDeserializer` entries do the same for the *sealed* "enum-like" types
 * (all parameterless objects): when the agent invents a style that isn't in the set — e.g.
 * `CompingStyle.ORGAN_PAD` at `$.tracks[].role.comping.style` — the unknown subtype falls back to
 * the default subtype rather than crashing the whole decode. coerceInputValues does NOT cover
 * polymorphic subtypes, only enums, so this is the polymorphic analog. Structural sealed types
 * (TrackRole/SoloMode/PitchEvolution, which carry parametrized data-class subtypes) are left
 * strict on purpose, so a genuinely malformed role still surfaces for self-correction.
 *
 * [Anomaly] gets the same net via [InertVoidAnomalyDeserializer]: the guide documents only the
 * "void"/"lick" types, so an invented one (e.g. `{"type":"sweep", ...}`) is a rare-hallucination
 * path — it degrades to a declared-but-auto-inert [VoidAnomaly] instead of crashing the whole
 * apply into the retry loop.
 *
 * Essential, default-less fields (name/bpm/rootNote/scaleType/genre/tracks) stay strict too.
 */
val vibeApplyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
    serializersModule = SerializersModule {
        polymorphicDefaultDeserializer(CompingStyle::class) { CompingStyle.PAD.serializer() }
        polymorphicDefaultDeserializer(LickMode::class) { LickMode.None.serializer() }
        polymorphicDefaultDeserializer(Anomaly::class) { InertVoidAnomalyDeserializer }
    }
}

/**
 * Fallback for an unknown [Anomaly] `"type"` (see the [vibeApplyJson] KDoc). The
 * `polymorphicDefaultDeserializer` mechanism can only pick a deserialization strategy — it cannot
 * construct an instance — so this delegates to [VoidAnomaly]'s serializer (unknown keys are
 * dropped by `ignoreUnknownKeys`) and then zeroes `probability`: the stock serializer alone would
 * keep the default 4% auto-fire chance. The result is a declared-but-auto-inert void — it never
 * fires on its own, though the manual anomaly trigger can still arm it.
 */
private object InertVoidAnomalyDeserializer : DeserializationStrategy<VoidAnomaly> {
    override val descriptor: SerialDescriptor = VoidAnomaly.serializer().descriptor
    override fun deserialize(decoder: Decoder): VoidAnomaly =
        VoidAnomaly.serializer().deserialize(decoder).copy(probability = 0f)
}

@LLMDescription("Arguments for applying a Pulsar vibe (built by editing a pulsar_get_vibe template) to the live engine.")
@Serializable
data class VibeApplyArgs(
    @property:LLMDescription("A complete Pulsar vibe as JSON. Get a template from pulsar_get_vibe, edit it, and pass the whole edited JSON here. Use an evocative ORIGINAL name — never a real artist/band/song/album name.")
    val vibeJson: String,
)

@LLMDescription("Result of applying a vibe. On failure, message explains what to fix; correct the JSON and call pulsar_apply_vibe again.")
@Serializable
data class VibeApplyResult(
    @property:LLMDescription("True if the vibe was valid and is now playing.")
    val success: Boolean,
    @property:LLMDescription("Status, or the exact reason the vibe was rejected so you can fix it.")
    val message: String,
    @property:LLMDescription("The name of the vibe that was applied, or null on failure.")
    val appliedName: String? = null,
)

/**
 * Cleans common AI artifacts (smart quotes, zero-width chars) before JSON decode. Safe to apply
 * unconditionally: unlike [unescapeDoubledQuotes], it never touches a backslash-escape, so a
 * field value that legitimately contains an escaped quote round-trips unchanged.
 */
internal fun sanitizeVibeJson(raw: String): String =
    raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace('“', '"')
        .replace('”', '"')
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace("​", "")
        .replace("‌", "")
        .replace("‍", "")
        .replace("﻿", "")

/**
 * Strips one layer of backslash-escaping from quotes, recovering a blob the model emitted as if
 * it were still wrapped in its own string literal. Only ever used as a fallback in [decodeVibe]:
 * applied unconditionally, this would corrupt any field whose value legitimately contains an
 * escaped quote.
 */
private fun unescapeDoubledQuotes(raw: String): String =
    raw.replace("\\\"", "\"").replace("\\'", "'")

/**
 * Decode + construct a Vibe; the schema and the model's init { require(...) } blocks are the
 * validator. Tries the sanitized JSON as-is first, the normal case, where any escaped quote in a
 * field value is legitimate content and must survive intact. Only falls back to stripping a layer
 * of backslash-escaping if that first decode fails, recovering the case where the model emitted
 * the whole blob as if it were still wrapped in its own string literal. On a genuine parse
 * failure (neither attempt decodes), the direct attempt's error is the more useful one to
 * surface, since the fallback's extra unescaping only mangles already-broken JSON further.
 */
fun decodeVibe(json: Json, raw: String): Result<Vibe> {
    val sanitized = sanitizeVibeJson(raw)
    val direct = runCatching { json.decodeFromString<Vibe>(sanitized) }
    if (direct.isSuccess) return direct
    val unescaped = runCatching { json.decodeFromString<Vibe>(unescapeDoubledQuotes(sanitized)) }
    return if (unescaped.isSuccess) unescaped else direct
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeApplyTool(
    // Lazy provider, not a direct PulsarFeature: injecting the feature eagerly here pulls
    // PulsarViewModel into the OrpheusAgentConfig tool-set construction, closing a DI cycle
    // (Pulsar -> PlaybackController -> AiOptionsViewModel -> OrpheusAgent -> tools -> Pulsar).
    // Mirrors PulsarSongEnding/PulsarMetadataProducer/PulsarVibePicker which take the same provider.
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val eventBus: VibeCreateEventBus,
    private val aiVibeArchive: AiVibeArchive,
) : ToolProvider {

    private val json = vibeApplyJson

    override val tool by lazy {
        object : Tool<VibeApplyArgs, VibeApplyResult>(
            argsType = typeToken<VibeApplyArgs>(),
            resultType = typeToken<VibeApplyResult>(),
            name = "pulsar_apply_vibe",
            description = """
                Apply a Pulsar vibe to the live beat machine and start it playing.
                Workflow: call pulsar_get_vibe to get a template, edit the JSON to match the
                requested feel (bpm, rootNote, scaleType, genre.customProgression, per-track engines,
                arrangement), then pass the complete edited JSON here.
                Rules: exactly 8 tracks; progression degrees 0..6; name must be an evocative ORIGINAL
                name (never a real artist/band/song/album).
                Instruments — keep each track's engine in its role family when you change it:
                  drums BD/SD/HH/NSE/PAR · bass WSH/VCF/PD/VA/DX · keys DX2 · lead DX3/WSH/FM/WTB ·
                  pad ENS/STR/CHD/GRN/ADD · texture MOD/PAR/SPK/SWM/NES/TRN.
                DX FAMILY (DX/DX2/DX3): 'harmonics' is NOT a tone knob — it is a 32-patch selector
                (quantized, auto-pinned). Set it deliberately to pick a patch; to land on patch index N
                use harmonics = N / (32 * 1.02). Never leave a DX engine's harmonics arbitrary or it
                loads a random patch and sounds wrong. Set harmonics/timbre/morph explicitly on any
                engine you swap in.
                If this returns success=false, read the message, fix the JSON, and call again.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeApplyArgs): VibeApplyResult {
                eventBus.emitGenerating()
                return decodeVibe(json, args.vibeJson).fold(
                    onSuccess = { vibe ->
                        // Persist the AI's creation immediately (best-effort, never throws) so it is
                        // retrievable later even if the song auto-advances past it, the app closes, or
                        // the apply below fails. Archives only on the DJ app (Android + JVM); a no-op
                        // elsewhere.
                        aiVibeArchive.archive(vibe.name, args.vibeJson)
                        // decodeVibe guards the parse/validation; guard the apply side-effects too, so an
                        // engine-wiring throw still emits Failed instead of escaping execute() and leaving
                        // the panel stuck on the Generating spinner with no error or retry.
                        try {
                            log.debug { "Applying agent vibe '${vibe.name}'" }
                            val pulsar = pulsarFeatureProvider()
                            pulsar.applyVibe(vibe)
                            pulsar.actions.setMix(0.8f)
                            eventBus.emitGenerated(vibe)
                            VibeApplyResult(true, "Applied and playing '${vibe.name}'.", vibe.name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn { "Vibe apply failed after decode: ${e.message}" }
                            eventBus.emitFailed(e.message ?: "unknown error")
                            VibeApplyResult(false, "Could not apply vibe: ${e.message}", null)
                        }
                    },
                    onFailure = { e ->
                        log.warn { "Vibe rejected: ${e.message}" }
                        eventBus.emitFailed(e.message ?: "unknown error")
                        VibeApplyResult(false, "Could not build vibe: ${e.message}", null)
                    },
                )
            }
        }
    }

    private val log = logging("VibeApplyTool")
}
