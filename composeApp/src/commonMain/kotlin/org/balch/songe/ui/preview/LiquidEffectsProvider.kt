package org.balch.songe.ui.preview

import org.balch.songe.features.viz.SwirlyViz
import org.balch.songe.ui.viz.VisualizationLiquidEffects
import org.jetbrains.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * PreviewParameterProvider for LiquidState.
 * 
 * Since LiquidState requires a composable context (rememberLiquidState()) to be created
 * with a real graphics layer, we can only provide null for previews.
 * The null case tests the fallback background path in Modifier.viz().
 * 
 * To see the actual liquid glassmorphism effects, run the app.
 */
class LiquidEffectsProvider : PreviewParameterProvider<VisualizationLiquidEffects> {
    override val values = sequenceOf(
        SwirlyViz.Default,
    )
}
