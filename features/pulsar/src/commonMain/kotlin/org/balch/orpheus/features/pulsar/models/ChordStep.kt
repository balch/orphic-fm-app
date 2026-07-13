package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

internal fun validateProgression(steps: List<ChordStep>, where: String) {
    require(steps.size in 1..12) {
        "$where size must be 1..12, got ${steps.size}"
    }
    require(steps.all { it.degree in 0..6 }) {
        "$where degrees must be 0..6 (I-VII), got ${steps.map { it.degree }}"
    }
    require(steps.all { it.glideRate in 0f..1f }) {
        "$where glideRate must be 0..1, got ${steps.map { it.glideRate }}"
    }
}

/**
 * One chord in a progression.
 * @param degree Scale degree 0..6 (I-VII).
 * @param glideRate Portamento applied when transitioning *into* this chord, 0..1.
 *   0 = instant change (default), 0.3 = smooth, 0.6+ = very slow slide.
 *   Only takes effect on tracks whose role honors the chord progression.
 */
@Serializable(with = ChordStepSerializer::class)
data class ChordStep(
    val degree: Int,
    val glideRate: Float = 0f,
)

/**
 * Decodes the canonical object form `{"degree":3,"glideRate":0.4}` AND the bare-number LLM
 * shorthand `3` (== `ChordStep(degree = 3, glideRate = 0f)`) — agents frequently emit a plain
 * scale-degree array for a progression instead of the full object form, and re-prompting them to
 * fix it burns tokens. Encoding always emits the object form; the wire format (persisted presets,
 * `pulsar_get_vibe` templates, `PulsarViewModel.persistJson`) is unchanged. Applying this
 * serializer at the class declaration (rather than per-property) means every field of type
 * [ChordStep] or `List<ChordStep>` — [GenreProfile.customProgression], [Section.customProgression] —
 * gets the leniency for free.
 */
object ChordStepSerializer : KSerializer<ChordStep> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.balch.orpheus.features.pulsar.models.ChordStep") {
            element<Int>("degree")
            element<Float>("glideRate", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: ChordStep) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.degree)
            encodeFloatElement(descriptor, 1, value.glideRate)
        }
    }

    override fun deserialize(decoder: Decoder): ChordStep {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonPrimitive -> ChordStep(degree = element.int)
                is JsonObject -> ChordStep(
                    degree = element["degree"]?.jsonPrimitive?.int
                        ?: throw SerializationException("ChordStep missing 'degree'"),
                    glideRate = element["glideRate"]?.jsonPrimitive?.floatOrNull ?: 0f,
                )
                else -> throw SerializationException("ChordStep: expected a number or an object")
            }
        }
        return decoder.decodeStructure(descriptor) {
            var degree = 0
            var glideRate = 0f
            while (true) {
                when (val i = decodeElementIndex(descriptor)) {
                    0 -> degree = decodeIntElement(descriptor, 0)
                    1 -> glideRate = decodeFloatElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("ChordStep: unknown index $i")
                }
            }
            ChordStep(degree, glideRate)
        }
    }
}

/**
 * Convenience builder: convert a series of scale degrees into [ChordStep]s
 * with no glide. Use this for the common `progression` form:
 *
 *   `customProgression = chords(0, 3, 5, 6)`
 *
 * For per-chord glides, build the list explicitly:
 *
 *   `customProgression = listOf(ChordStep(0), ChordStep(3, glideRate = 0.4f), ...)`
 */
fun chords(vararg degrees: Int): List<ChordStep> = degrees.map { ChordStep(it) }
