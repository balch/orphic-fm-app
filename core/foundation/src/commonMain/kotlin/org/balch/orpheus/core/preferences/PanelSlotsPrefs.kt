package org.balch.orpheus.core.preferences

import kotlinx.serialization.Serializable

/**
 * The desktop panel slots as last left, by panel id. [open] is in slot order; [lastUsed] and
 * [recents] are most recent first. A null [railVisible] means the user never chose, so the
 * window width decides.
 */
@Serializable
data class PanelSlotsPrefs(
    val open: List<String> = emptyList(),
    val lastUsed: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val railVisible: Boolean? = null,
    val recentsStripVisible: Boolean = true,
)
