package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wire shape of [LickStep]. Declared through a surrogate so the descriptor still lists every
 * field for schema generators. `rest` is nullable only to tell "key absent" (a legacy file,
 * where a negative degree meant a rest) from an explicit value. `scaleDegree` may be left off a
 * rest, since models drop fields that mean nothing.
 */
@Serializable
@SerialName("org.balch.orpheus.features.pulsar.models.LickStep")
private class LickStepSurrogate(
    val scaleDegree: Int? = null,
    val duration: Float,
    val velocity: Float = 0.8f,
    val glideRate: Float = -1f,
    val hitProbability: Float = 1f,
    val rest: Boolean? = null,
)

/** Reads old `{"scaleDegree":-1}` rests as rests; always writes `rest` so notes below the root survive. */
internal object LickStepSerializer : KSerializer<LickStep> {
    override val descriptor: SerialDescriptor = LickStepSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LickStep) {
        encoder.encodeSerializableValue(
            LickStepSurrogate.serializer(),
            LickStepSurrogate(
                value.scaleDegree, value.duration, value.velocity,
                value.glideRate, value.hitProbability, value.rest,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): LickStep {
        val s = decoder.decodeSerializableValue(LickStepSurrogate.serializer())
        val degree = s.scaleDegree
            ?: if (s.rest == true) 0 else throw SerializationException("LickStep needs scaleDegree unless rest is true")
        return LickStep(
            scaleDegree = degree,
            duration = s.duration,
            velocity = s.velocity,
            glideRate = s.glideRate,
            hitProbability = s.hitProbability,
            rest = s.rest ?: (degree < 0),
        )
    }
}
