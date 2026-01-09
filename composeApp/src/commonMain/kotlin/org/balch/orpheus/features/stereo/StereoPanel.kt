package org.balch.orpheus.features.stereo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalToggle
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

// Darker cyan for PAN panel
private val PanColor = Color(0xFF008B8B)  // Dark cyan

/**
 * StereoPanel consuming feature() interface.
 */
@Composable
fun StereoPanel(
    feature: StereoFeature,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "PAN",
        color = PanColor,
        expandedTitle = "Stereo",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        // Side-by-side layout: Mode toggle | Pan knob
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode toggle: Voice Pan / Stereo Delays
            VerticalToggle(
                topLabel = "VOICE",
                bottomLabel = "DELAY",
                isTop = uiState.mode == StereoMode.VOICE_PAN,
                onToggle = { isVoicePan ->
                    actions.onModeChange(
                        if (isVoicePan) StereoMode.VOICE_PAN else StereoMode.STEREO_DELAYS
                    )
                },
                color = PanColor,
                controlId = "stereo_mode",
                enabled = true
            )

            // Master Pan knob
            RotaryKnob(
                value = (uiState.masterPan + 1f) / 2f,
                onValueChange = { normalized ->
                    actions.onMasterPanChange((normalized * 2f) - 1f)
                },
                label = "PAN",
                controlId = "stereo_pan",
                size = 64.dp,
                progressColor = PanColor
            )
        }
    }
}

@Preview(widthDp = 160, heightDp = 240)
@Composable
fun StereoPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        StereoPanel(
            feature = StereoViewModel.previewFeature(),
            isExpanded = true
        )
    }
}
