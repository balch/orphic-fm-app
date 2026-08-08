package org.balch.orpheus.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquefiableVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/**
 * Preview wrapper with a visualization-style gradient background.
 * Shows the panel with a more colorful background for testing liquid effects.
 *
 * Delegates the ambient environment to [OrpheusTheme] -- see its kdoc for why an unthemed
 * preview misreports text metrics, and for the `fontScale` annotation pattern.
 */
@Composable
fun LiquidPreviewContainerWithGradient(
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects.Default,
    modifier: Modifier = Modifier.size(400.dp, 300.dp),
    content: @Composable () -> Unit
) {
    OrpheusTheme {
        val liquidState: LiquidState = rememberLiquidState()

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            // Colorful gradient background to see the liquid blur effect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                OrpheusColors.neonMagenta.copy(alpha = 0.4f),
                                OrpheusColors.electricBlue.copy(alpha = 0.3f),
                                OrpheusColors.darkVoid
                            )
                        )
                    )
                    .liquefiableVizEffects(liquidState)
            )

            CompositionLocalProvider(
                LocalLiquidState provides liquidState,
                LocalLiquidEffects provides effects
            ) {
                content()
            }
        }
    }
}
