package org.balch.orpheus.features.visualizations.viz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.Visualization

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class OffViz : Visualization {
    override val id = "off"
    override val name = "Off"
    override val color = Color.Gray // Neutral color
    override val knob1Label = "N/A"
    override val knob2Label = "N/A"
    override val liquidEffects = VisualizationLiquidEffects.Off

    override fun setKnob1(value: Float) {}
    override fun setKnob2(value: Float) {}

    override fun onActivate() {} // No-op
    override fun onDeactivate() {} // No-op

    @Composable
    override fun Content(modifier: Modifier) {
        // Render nothing or just the base background color if needed, 
        // but typically the parent container handles the base background.
    }
}
