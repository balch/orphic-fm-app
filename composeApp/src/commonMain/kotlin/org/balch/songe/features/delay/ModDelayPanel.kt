package org.balch.songe.features.delay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.songe.ui.panels.CollapsibleColumnPanel
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.RotaryKnob
import org.balch.songe.ui.widgets.VerticalToggle

/**
 * Smart wrapper that connects DelayViewModel to the layout. Collects state and dispatches events.
 */
@Composable
fun ModDelayPanel(modifier: Modifier = Modifier, viewModel: DelayViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsState()

    ModDelayPanelLayout(
        time1 = state.time1,
        onTime1Change = viewModel::onTime1Change,
        mod1 = state.mod1,
        onMod1Change = viewModel::onMod1Change,
        time2 = state.time2,
        onTime2Change = viewModel::onTime2Change,
        mod2 = state.mod2,
        onMod2Change = viewModel::onMod2Change,
        feedback = state.feedback,
        onFeedbackChange = viewModel::onFeedbackChange,
        mix = state.mix,
        onMixChange = viewModel::onMixChange,
        isLfoSource = state.isLfoSource,
        onSourceChange = viewModel::onSourceChange,
        isTriangleWave = state.isTriangleWave,
        onWaveformChange = viewModel::onWaveformChange,
        modifier = modifier
    )
}

/**
 * Mod Delay panel with dual delay lines, modulation, and mix controls. Layout: Row 1: MOD 1, MOD 2,
 * LFO/SELF toggle, TRI/SQR toggle Row 2: TIME 1, TIME 2, FB, MIX
 */
@Composable
fun ModDelayPanelLayout(
    time1: Float,
    onTime1Change: (Float) -> Unit,
    mod1: Float,
    onMod1Change: (Float) -> Unit,
    time2: Float,
    onTime2Change: (Float) -> Unit,
    mod2: Float,
    onMod2Change: (Float) -> Unit,
    feedback: Float,
    onFeedbackChange: (Float) -> Unit,
    mix: Float,
    onMixChange: (Float) -> Unit,
    isLfoSource: Boolean,
    onSourceChange: (Boolean) -> Unit,
    isTriangleWave: Boolean,
    onWaveformChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CollapsibleColumnPanel(
        title = "DELAY",
        color = SongeColors.warmGlow,
        expandedTitle = "Mod Delay",
        initialExpanded = true,
        expandedWidth = 240.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Row 1: MOD 1, MOD 2, LFO/SELF toggle, TRI/SQR toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                RotaryKnob(
                    value = mod1,
                    onValueChange = onMod1Change,
                    label = "MOD 1",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
                RotaryKnob(
                    value = mod2,
                    onValueChange = onMod2Change,
                    label = "MOD 2",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
                // Add top padding to toggles to align with knob centers
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    VerticalToggle(
                        topLabel = "LFO",
                        bottomLabel = "SELF",
                        isTop = isLfoSource,
                        onToggle = { onSourceChange(it) },
                        color = SongeColors.warmGlow
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    VerticalToggle(
                        topLabel = "TRI",
                        bottomLabel = "SQR",
                        isTop = isTriangleWave,
                        onToggle = { onWaveformChange(it) },
                        color = SongeColors.warmGlow
                    )
                }
            }

            // Row 2: TIME 1, TIME 2, FB, MIX
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RotaryKnob(
                    value = time1,
                    onValueChange = onTime1Change,
                    label = "TIME 1",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
                RotaryKnob(
                    value = time2,
                    onValueChange = onTime2Change,
                    label = "TIME 2",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
                RotaryKnob(
                    value = feedback,
                    onValueChange = onFeedbackChange,
                    label = "FB",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
                RotaryKnob(
                    value = mix,
                    onValueChange = onMixChange,
                    label = "MIX",
                    controlId = null,
                    size = 40.dp,
                    progressColor = SongeColors.warmGlow
                )
            }
        }
    }
}
