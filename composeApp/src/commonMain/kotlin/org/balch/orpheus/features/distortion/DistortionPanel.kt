package org.balch.orpheus.features.distortion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter
import kotlin.math.roundToInt

/**
 * DistortionPanel consuming feature() interface.
 */
@Composable
fun DistortionPanel(
    feature: DistortionFeature = DistortionViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "VOL",
        color = OrpheusColors.neonMagenta,
        expandedTitle = "Distortion",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        DistortionPanelContent(uiState, actions)
    }
}

@Composable
private fun DistortionPanelContent(
    uiState: DistortionUiState,
    actions: DistortionPanelActions
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Top Row: Drive, Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RotaryKnob(
                value = uiState.drive,
                onValueChange = actions.onDriveChange,
                label = "DRIVE",
                controlId = ControlIds.DRIVE,
                size = 56.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            RotaryKnob(
                value = uiState.volume,
                onValueChange = actions.onVolumeChange,
                label = "VOL",
                controlId = ControlIds.MASTER_VOLUME,
                size = 56.dp,
                progressColor = OrpheusColors.neonMagenta
            )
        }

        // Bottom Row: Mix, Peak LED
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.mix,
                onValueChange = actions.onMixChange,
                label = "MIX",
                controlId = ControlIds.DISTORTION_MIX,
                size = 56.dp,
                progressColor = OrpheusColors.neonMagenta
            )

            // Peak LED indicator
            PeakLed(peak = uiState.peak)
        }
    }
}

/**
 * LED indicator that shows peak level with color coding. Blue: Normal (< 0.8) Yellow: Hot (0.8 -
 * 0.95) Red: Clipping (> 0.95)
 */
@Composable
private fun PeakLed(peak: Float, modifier: Modifier = Modifier) {
    val ledColor =
        when {
            peak > 0.95f -> Color.Red
            peak > 0.8f -> Color.Yellow
            else -> OrpheusColors.electricBlue
        }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            // Subtle glow
            Box(
                modifier =
                    Modifier.size(28.dp)
                        .blur(8.dp)
                        .background(ledColor.copy(alpha = 0.4f), CircleShape)
            )
            // LED core
            Box(modifier = Modifier.size(16.dp).background(ledColor, CircleShape))
            // Highlight
            Box(
                modifier =
                    Modifier.size(6.dp)
                        .align(Alignment.TopCenter)
                        .background(Color.White.copy(alpha = 0.4f), CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "PEAK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = ((peak * 100).roundToInt() / 100.0).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = ledColor
        )
    }
}

@Preview(widthDp = 240, heightDp = 200)
@Composable
fun DistortionPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        DistortionPanel(
            feature = DistortionViewModel.previewFeature()
        )
    }
}
