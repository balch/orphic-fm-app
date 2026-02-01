package org.balch.orpheus.features.visualizations.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

import org.balch.orpheus.features.visualizations.viz.GalaxyViz
import org.balch.orpheus.features.visualizations.viz.LavaLampViz
import org.balch.orpheus.features.visualizations.viz.SwirlyViz

import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects

/**
 * PreviewParameterProvider for VisualizationLiquidEffects.
 * Provides different effect configurations for preview variants.
 */
class LiquidEffectsProvider : PreviewParameterProvider<VisualizationLiquidEffects> {
    private val effectsList = listOf(
        VisualizationLiquidEffects.Off,
        SwirlyViz.Default,
        GalaxyViz.Default,
        LavaLampViz.Default,
    )
    override val values = effectsList.asSequence()
}


