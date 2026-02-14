package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionUiState
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.AppTitleTreatment

/**
 * Compact header panel for portrait mode mobile layout.
 * 
 * Features:
 * - Rendered under status bar (padding applied)
 * - Title with liquid glass effect
 * - Preset panel (collapsible) 
 * - Master volume knob with peak LED
 * - Optimized for narrow portrait width
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitHeaderPanel(
    distortionFeature: DistortionFeature = DistortionViewModel.feature(),
    liquidState: LiquidState = rememberLiquidState(),
    effects: VisualizationLiquidEffects = VisualizationLiquidEffects(),
    modifier: Modifier = Modifier
) {
    val state by distortionFeature.stateFlow.collectAsState()
    val peakLevel = state.peak
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp)
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.top,
                frostAmount = 6.dp,
                color = OrpheusColors.darkVoid,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Title
        AppTitleTreatment(
            effects = effects,
            showSizeEffects = false,
        )

        // Right: Peak LED
        PeakLed(level = peakLevel)
    }
}

@Composable
private fun PeakLed(level: Float) {
    val size = 10.dp
    val active = level > 0.01f
    val clipping = level > 0.95f
    
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                when {
                    clipping -> Color.Red
                    active -> OrpheusColors.neonCyan.copy(alpha = 0.5f + (level * 0.5f))
                    else -> OrpheusColors.panelBackground
                }
            )
            .border(
                1.dp, 
                if (clipping) Color.Red else OrpheusColors.neonCyan.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.CircleShape
            )
    )
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 70)
@Composable
private fun CompactPortraitHeaderPanelPreview() {
    val previewFeature = DistortionViewModel.previewFeature(DistortionUiState(peak = 0.5f))
    CompactPortraitHeaderPanel(
        distortionFeature = previewFeature,
        liquidState = rememberLiquidState(),
        effects = VisualizationLiquidEffects()
    )
}
