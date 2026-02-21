package org.balch.orpheus.core.features

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
 * Self-registering UI panel descriptor.
 * Feature modules implement this interface and register via @ContributesIntoSet.
 *
 * Visibility, ordering, and expansion state are controlled by panel sets,
 * not by individual panel properties.
 */
interface FeaturePanel {
    val panelId: PanelId
    val description: String
    val weight: Float
    val label: String
    val color: Color

    @Composable
    fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    )
}

/**
 * Creates a preview [FeaturePanel] with the given properties and composable content.
 */
fun featurePanelPreview(
    panelId: PanelId,
    description: String = "",
    weight: Float = 1f,
    label: String = panelId.id,
    color: Color = Color.White,
    content: @Composable (Modifier, Boolean, (Boolean) -> Unit, (Boolean) -> Unit) -> Unit
): FeaturePanel = object : FeaturePanel {
    override val panelId = panelId
    override val description = description
    override val weight = weight
    override val label = label
    override val color = color

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
