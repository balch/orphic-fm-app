package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable

/**
 * One saved mix for a vibe. [values] and [originals] are keyed by the DJ app's preset field keys;
 * [originals] holds the vibe's own values at capture time, for the "changed" comparison.
 */
@Serializable
data class VibePreset(
    val id: Int,
    val name: String,
    val capturedAtMs: Long,
    val values: Map<String, Float>,
    val originals: Map<String, Float> = emptyMap(),
)

/** A vibe's preset book. Ids come from [nextId] and are never reused after a delete. */
@Serializable
data class VibePresetSlot(
    val presets: List<VibePreset> = emptyList(),
    val selectedId: Int? = null,
    val nextId: Int = 1,
)
