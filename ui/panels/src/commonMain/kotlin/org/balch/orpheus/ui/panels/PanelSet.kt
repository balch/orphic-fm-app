package org.balch.orpheus.core.panels

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.PanelId

/**
 * Configuration for a single panel within a [PanelSet].
 */
@Serializable
data class PanelConfig(
    val panelId: String,
    val expanded: Boolean = true,
    val weight: Float? = null,
)

/**
 * A named collection of panel configurations that controls
 * which panels are visible, their ordering, expansion state,
 * and optional weight overrides.
 *
 * Panels not mentioned in the set are hidden entirely.
 * Ordering follows declaration order.
 */
@Serializable
data class PanelSet(
    val name: String,
    val panels: List<PanelConfig> = emptyList(),
    val isFactory: Boolean = false,
) {
    /** Ordered list of visible panel IDs (declaration order). */
    val visibleIds: List<PanelId>
        get() = panels.map { PanelId(it.panelId) }

    /** Subset of visible panels that should be expanded. */
    val expandedIds: Set<PanelId>
        get() = panels.filter { it.expanded }.map { PanelId(it.panelId) }.toSet()

    /** Weight overrides for panels (null entries omitted). */
    val weightOverrides: Map<PanelId, Float>
        get() = panels.filter { it.weight != null }.associate { PanelId(it.panelId) to it.weight!! }
}
