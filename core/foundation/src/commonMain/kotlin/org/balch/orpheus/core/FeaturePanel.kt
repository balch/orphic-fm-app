package org.balch.orpheus.core

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Available panels that can be expanded/collapsed.
 */
@LLMDescription("Panels in the app the can be expanded or collapsed.")
enum class PanelId {
    @LLMDescription("Panel allowing user to select a patch")
    PRESETS,
    @LLMDescription("Assign MIDI commands to control the synthesizer")
    MIDI,
    @LLMDescription("Display visualizations linked to the sound in the background")
    VIZ,
    @LLMDescription("Algorithmic Evolution Panel")
    EVO,
    @LLMDescription("Provide wave patterns to produce sounds")
    LFO,
    @LLMDescription("Add repeating lines to sounds")
    DELAY,
    @LLMDescription("Add spatial reverb effect")
    REVERB,
    @LLMDescription("Control volume characteristics of sounds")
    DISTORTION,
    @LLMDescription("Add texture to sounds")
    RESONATOR,
    @LLMDescription("Tidal Coding Panel for REPL")
    CODE,
    @LLMDescription("Panel allowing user to select a patch")
    AI,
    @LLMDescription("Drum Patterns Panel")
    BEATS,
    @LLMDescription("Drum Tuning Panel")
    DRUMS,
    @LLMDescription("Granular Molecule Synthesis")
    GRAINS,
    @LLMDescription("Record and replay audio")
    LOOPER,
    @LLMDescription("Cross Modulation")
    WARPS,
    @LLMDescription("Random music generator")
    FLUX,
    @LLMDescription("Assigns sounds to Flux outputs")
    FLUX_TRIGGERS,
    @LLMDescription("Speech synthesis panel showing AI speech output")
    SPEECH,
    @LLMDescription("Modulation tweaks panel")
    TWEAKS
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
        val sorted = unlinked.sortedBy { it.panelId.name }.toMutableList()
        for (panel in linked.sortedBy { it.panelId.name }) {
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
    linkedFeature: PanelId? = null,
    weight: Float = 1f,
    defaultExpanded: Boolean = false,
    compactPortrait: CompactPortraitConfig? = null,
    content: @Composable (Modifier, Boolean, (Boolean) -> Unit, (Boolean) -> Unit) -> Unit
): FeaturePanel = object : FeaturePanel {
    override val panelId = panelId
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
