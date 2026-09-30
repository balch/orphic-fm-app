package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable

/** The DJ app's saved vibe rotation, by display name. Names that no longer resolve are dropped when merged. */
@Serializable
data class VibePlaylistPrefs(
    val order: List<String> = emptyList(),
    val removed: Set<String> = emptySet(),
)
