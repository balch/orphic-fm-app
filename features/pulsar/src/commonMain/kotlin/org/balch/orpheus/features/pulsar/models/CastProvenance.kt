package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.OrpheusEngineId

/**
 * How the AI's cast rolled a vibe's skeleton: the same seed, mode, tags and pins cast the same
 * skeleton again. A record only; nothing in the engine reads it.
 */
@Serializable
data class CastProvenance(
    val seed: Long,
    /** BED_FIXED or ALL_ROLLED. */
    val mode: String,
    val styleTags: List<String> = emptyList(),
    val pins: List<CastPinRecord> = emptyList(),
)

/** One pin of a cast: [track] held to a part ([role]) and/or an EDM [engine]. */
@Serializable
data class CastPinRecord(
    val track: Int,
    val role: String? = null,
    val engine: OrpheusEngineId? = null,
)
