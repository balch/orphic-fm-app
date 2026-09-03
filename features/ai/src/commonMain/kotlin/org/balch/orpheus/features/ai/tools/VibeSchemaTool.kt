package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.structure.json.JsonStructure
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import ai.koog.serialization.typeToken
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * Json used to GENERATE the schema. Mirrors Koog's `JsonStructure.defaultJson` EXCEPT the class
 * discriminator: Koog defaults to "kind", but the apply path (`vibeApplyJson`) decodes with kotlinx's
 * default "type". If the schema advertised "kind" while the decoder read "type", an agent that follows
 * the schema would emit `{"kind":"...TrackRole.Percussive"}` and the decode would throw "Class
 * discriminator was missing" for EVERY sealed type (TrackRole/SoloMode/PitchEvolution/CompingStyle/
 * LickMode). Keeping the schema's discriminator equal to the decoder's keeps the two in lockstep.
 * (Asserted by VibeSchemaToolTest against `vibeApplyJson.configuration.classDiscriminator`.)
 */
@OptIn(ExperimentalSerializationApi::class)
private val schemaGenJson = Json {
    prettyPrint = true
    explicitNulls = false
    isLenient = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
}

/**
 * The Vibe JSON schema, generated from the @Serializable model so it never drifts from the code.
 * Teaches the agent valid field types and enum vocabularies (e.g. album/rootNote/scaleType are
 * enums, not free text — the cause of "Album does not contain element 'Heartland Echoes'").
 */
internal fun generateVibeSchemaJson(): String {
    val structure = JsonStructure.create<Vibe>(
        json = schemaGenJson,
        schemaGenerator = StandardJsonSchemaGenerator.Default,
        // JSON Schema can't express Int map keys, and Section.trackOverrides is Map<Int, _>, so the
        // generator chokes on it. We exclude it from the GENERATED schema and document it explicitly
        // in TRACK_OVERRIDES_NOTE instead — it is still fully supported on the apply path.
        excludedProperties = setOf("org.balch.orpheus.features.pulsar.models.Section.trackOverrides"),
    )
    return structure.schema.schema.toString()
}

/**
 * Hand-written addendum for `Section.trackOverrides`, which is omitted from the generated schema
 * above (its Int map keys can't be expressed in JSON Schema) but IS a supported, important feature.
 *
 * `internal val`, not `const val`: interpolates JSON examples from [VibeGuideExamples], and Kotlin
 * const vals cannot hold string templates.
 */
internal val TRACK_OVERRIDES_NOTE: String = """
NOTE — `trackOverrides` (a field on each arrangement Section) is NOT in the schema above because the
schema tool can't represent its integer keys, but it IS fully supported and is how you change ONE
track's behaviour within a single section (e.g. pedal a hook on the tonic, drop a track's density in a
breakdown, swap a track's comping in the chorus). It auto-restores when the section ends.
Shape — an object keyed by the track index (0-7) as a string:
  "trackOverrides": ${VibeGuideExamples.TRACK_OVERRIDES_EXAMPLE}
TrackSectionOverride fields (all optional, override only what you set):
  density, volume, reverbSend, delaySend, envelopeProfile, compingStyle, sectionInversion, arpMode,
  chordFollow, holdProbability, holdLengthMin, holdLengthMax.
IMPORTANT — unlike the bare-string enum fields beside it, `compingStyle` is a polymorphic OBJECT,
written exactly like Section.compingStyle in the schema above (NOT a bare string):
  "compingStyle": ${VibeGuideExamples.COMPING_STYLE_EXAMPLE}
  Allowed values: PAD, FUNK_STABS, ROCK_DOWNBEATS, SKA_UPSTROKES, BLUES_SHUFFLE, JAZZ_COMP,
  REGGAE_SKANK, GOSPEL_STABS.
"""

/** The full agent-facing vibe reference: generated schema + the trackOverrides addendum. */
internal fun vibeSchemaReference(): String = generateVibeSchemaJson() + "\n" + TRACK_OVERRIDES_NOTE.trim()

@LLMDescription("Arguments for fetching the Pulsar vibe schema. Takes no input — pass an empty object.")
@Serializable
class VibeSchemaArgs

@LLMDescription("The JSON schema describing a Pulsar vibe: field types and the exact allowed enum values.")
@Serializable
data class VibeSchemaResult(
    @property:LLMDescription("The Vibe JSON schema, as a JSON string. Use it to choose valid enum values.")
    val schema: String,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class VibeSchemaTool : ToolProvider {

    // Generated once, lazily: walking the whole Vibe graph is not free, and the schema is static.
    private val schemaJson by lazy { vibeSchemaReference() }

    override val tool by lazy {
        object : Tool<VibeSchemaArgs, VibeSchemaResult>(
            argsType = typeToken<VibeSchemaArgs>(),
            resultType = typeToken<VibeSchemaResult>(),
            name = "pulsar_vibe_schema",
            description = """
                Return the JSON schema for a Pulsar vibe: every field's type and the exact allowed
                values for enum fields (album, rootNote, scaleType, envelopeType, genre.progressionStyle,
                per-engine engineId, the track role kinds, etc.). Use this when unsure of a field's type or allowed values — e.g. 'album' is one of STEALTH/RIF/ZERO_TO_ONE,
                NOT a title (the title goes in 'name'). Use it together with pulsar_get_vibe, which gives
                a concrete known-good template to edit.
                Call pulsar_vibe_guide first for what the fields mean and which template to start from.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: VibeSchemaArgs): VibeSchemaResult = VibeSchemaResult(schemaJson)
        }
    }

    private val log = logging("VibeSchemaTool")
}
