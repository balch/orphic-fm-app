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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalToggle

// Darker cyan for PAN panel
private val PanColor = Color(0xFF008B8B)  // Dark cyan

/**
 * Smart wrapper that connects StereoViewModel to the layout.
 */
@Composable
fun StereoPanel(
    modifier: Modifier = Modifier,
    viewModel: StereoViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()

    StereoPanelLayout(
        mode = state.mode,
        onModeChange = viewModel::onModeChange,
        masterPan = state.masterPan,
        onMasterPanChange = viewModel::onMasterPanChange,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

/**
 * Stereo panel with mode switch and master pan control.
 * 
 * Layout:
 * - Title "Stereo"
 * - Mode toggle: Voice Pan / Stereo Delays
 * - Large master pan knob
 */
@Composable
fun StereoPanelLayout(
    mode: StereoMode,
    onModeChange: (StereoMode) -> Unit,
    masterPan: Float,
    onMasterPanChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    CollapsibleColumnPanel(
        title = "PAN",
        color = PanColor,
        expandedTitle = "Stereo",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedWidth = 120.dp,
        modifier = modifier
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
                isTop = mode == StereoMode.VOICE_PAN,
                onToggle = { isVoicePan ->
                    onModeChange(
                        if (isVoicePan) StereoMode.VOICE_PAN else StereoMode.STEREO_DELAYS
                    )
                },
                color = PanColor,
                controlId = "stereo_mode",
                enabled = true
            )

            // Master Pan knob
            RotaryKnob(
                value = (masterPan + 1f) / 2f,
                onValueChange = { normalized ->
                    onMasterPanChange((normalized * 2f) - 1f)
                },
                label = "PAN",
                controlId = "stereo_pan",
                size = 64.dp,
                progressColor = PanColor
            )
        }
    }
}
