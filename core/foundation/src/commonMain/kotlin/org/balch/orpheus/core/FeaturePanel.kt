package org.balch.orpheus.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.jvm.JvmInline

/**
 * Identity for a UI panel. Each feature module defines its own ID string.
 * The companion object provides constants for cross-module references.
 */
@JvmInline
value class PanelId(val id: String) {
    val name: String get() = id

    companion object {
        val PRESETS = PanelId("presets")
        val MIDI = PanelId("midi")
        val VIZ = PanelId("viz")
        val EVO = PanelId("evo")
        val LFO = PanelId("lfo")
        val DELAY = PanelId("delay")
        val REVERB = PanelId("reverb")
        val DISTORTION = PanelId("distortion")
        val RESONATOR = PanelId("resonator")
        val CODE = PanelId("code")
        val AI = PanelId("ai")
        val BEATS = PanelId("beats")
        val DRUMS = PanelId("drums")
        val GRAINS = PanelId("grains")
        val LOOPER = PanelId("looper")
        val WARPS = PanelId("warps")
        val FLUX = PanelId("flux")
        val FLUX_TRIGGERS = PanelId("flux_triggers")
        val SPEECH = PanelId("speech")
        val TWEAKS = PanelId("tweaks")
    }
}

/**
 * Position group for panel ordering within the header row.
 */
enum class PanelPosition { FIRST, START, MID, END, LAST }

/**
 * Configuration for compact portrait panel display.
 */
data class CompactPortraitConfig(
    val label: String,
    val color: Color,
    val order: Int,
)

/**
 * Self-registering UI panel descriptor.
 * Feature modules implement this interface and register via @ContributesIntoSet.
 */
interface FeaturePanel {
    val panelId: PanelId
    val description: String
    val position: PanelPosition
    val linkedFeature: PanelId?
    val weight: Float
    val defaultExpanded: Boolean
    val compactPortrait: CompactPortraitConfig? get() = null

    @Composable
    fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    )
}

/**
 * Sorts panels by position group, then alphabetically within each group,
 * with linked panels inserted immediately after their target.
 */
fun sortPanels(panels: Set<FeaturePanel>): List<FeaturePanel> {
    val grouped = panels.groupBy { it.position }
    return PanelPosition.entries.flatMap { position ->
        val group = grouped[position] ?: emptyList()
        val (linked, unlinked) = group.partition { it.linkedFeature != null }
        val sorted = unlinked.sortedBy { it.panelId.id }.toMutableList()
        for (panel in linked.sortedBy { it.panelId.id }) {
            val targetIndex = sorted.indexOfFirst { it.panelId == panel.linkedFeature }
            if (targetIndex >= 0) {
                sorted.add(targetIndex + 1, panel)
            } else {
                sorted.add(panel)
            }
        }
        sorted
    }
}

/**
 * Filters and sorts panels that participate in compact portrait mode.
 */
fun sortCompactPanels(panels: Collection<FeaturePanel>): List<FeaturePanel> =
    panels.filter { it.compactPortrait != null }
        .sortedBy { it.compactPortrait!!.order }

/**
 * Creates a preview [FeaturePanel] with the given properties and composable content.
 */
fun featurePanelPreview(
    panelId: PanelId,
    position: PanelPosition,
    description: String = "",
    linkedFeature: PanelId? = null,
    weight: Float = 1f,
    defaultExpanded: Boolean = false,
    compactPortrait: CompactPortraitConfig? = null,
    content: @Composable (Modifier, Boolean, (Boolean) -> Unit, (Boolean) -> Unit) -> Unit
): FeaturePanel = object : FeaturePanel {
    override val panelId = panelId
    override val description = description
    override val position = position
    override val linkedFeature = linkedFeature
    override val weight = weight
    override val defaultExpanded = defaultExpanded
    override val compactPortrait = compactPortrait

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        content(modifier, isExpanded, onExpandedChange, onDialogActiveChange)
    }
}
