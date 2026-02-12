package org.balch.orpheus.features.distortion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.StereoSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalToggle
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
        expandedTitle = "Mix",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Column 1: Drive & Mix
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RotaryKnob(
                    value = uiState.drive,
                    onValueChange = actions.setDrive,
                    label = "DISTORTION",
                    controlId = DistortionSymbol.DRIVE.controlId.key,
                    size = 56.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
                RotaryKnob(
                    value = uiState.mix,
                    onValueChange = actions.setMix,
                    label = "MIX",
                    controlId = DistortionSymbol.MIX.controlId.key,
                    size = 56.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
            }

            // Column 2: Volume & Pan
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RotaryKnob(
                    value = uiState.volume,
                    onValueChange = actions.setVolume,
                    label = "VOL",
                    controlId = StereoSymbol.MASTER_VOL.controlId.key,
                    size = 56.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
                RotaryKnob(
                    value = (uiState.masterPan + 1f) / 2f,
                    onValueChange = { normalized ->
                        actions.setMasterPan((normalized * 2f) - 1f)
                    },
                    label = "PAN",
                    controlId = StereoSymbol.MASTER_PAN.controlId.key,
                    size = 56.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
            }

            // Column 3: Peak LED & Mode Toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Peak LED indicator
                PeakLed(
                    peak = uiState.peak
                )

                // Mode toggle: Voice Pan / Stereo Delays
                VerticalToggle(
                    topLabel = "VOICE",
                    bottomLabel = "DELAY",
                    isTop = uiState.mode == StereoMode.VOICE_PAN,
                    onToggle = { isVoicePan ->
                        actions.setMode(
                            if (isVoicePan) StereoMode.VOICE_PAN else StereoMode.STEREO_DELAYS
                        )
                    },
                    color = OrpheusColors.neonMagenta,
                )
            }
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
            text = ((peak * 100).roundToInt() / 100.0).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = ledColor,
            maxLines = 1,
        )
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun DistortionPanelPreview() {
    DistortionPanel(
        feature = DistortionViewModel.previewFeature()
    )
}