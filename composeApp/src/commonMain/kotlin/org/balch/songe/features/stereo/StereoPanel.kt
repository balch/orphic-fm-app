package org.balch.songe.features.stereo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.songe.core.audio.StereoMode
import org.balch.songe.ui.panels.CollapsibleColumnPanel
import org.balch.songe.ui.widgets.RotaryKnob
import org.balch.songe.ui.widgets.VerticalToggle

// Darker cyan for PAN panel
private val PanColor = Color(0xFF008B8B)  // Dark cyan

/**
 * Smart wrapper that connects StereoViewModel to the layout.
 */
@Composable
fun StereoPanel(
    modifier: Modifier = Modifier,
    viewModel: StereoViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    StereoPanelLayout(
        mode = state.mode,
        onModeChange = viewModel::onModeChange,
        masterPan = state.masterPan,
        onMasterPanChange = viewModel::onMasterPanChange,
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    CollapsibleColumnPanel(
        title = "PAN",
        color = PanColor,
        expandedTitle = "Stereo",
        initialExpanded = false,
        expandedWidth = 120.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {

            Spacer(modifier = Modifier.height(8.dp))

            // Mode toggle: Voice Pan / Stereo Delays
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
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
                    enabled = true  // Delay mode not yet implemented, but toggle enabled
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large Master Pan knob
            // Convert -1..1 range to 0..1 for RotaryKnob, display as pan position
            RotaryKnob(
                value = (masterPan + 1f) / 2f,  // -1..1 -> 0..1
                onValueChange = { normalized ->
                    onMasterPanChange((normalized * 2f) - 1f)  // 0..1 -> -1..1
                },
                label = "PAN",
                controlId = "stereo_pan",
                size = 64.dp,
                progressColor = PanColor
            )
        }
    }
}
