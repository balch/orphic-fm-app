package org.balch.songe.features.viz

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.songe.ui.viz.Visualization

@Inject
@ContributesIntoSet(AppScope::class)
class OffViz : Visualization {
    override val id = "off"
    override val name = "Off"
    override val color = Color.Gray // Neutral color
    override val knob1Label = "N/A"
    override val knob2Label = "N/A"

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
