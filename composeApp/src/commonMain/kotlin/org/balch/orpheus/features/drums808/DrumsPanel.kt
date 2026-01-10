package org.balch.orpheus.features.drums808

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun DrumsPanel(
    drumFeature: DrumFeature = DrumViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state by drumFeature.stateFlow.collectAsState()
    val actions = drumFeature.actions

    CollapsibleColumnPanel(
        title = "808",
        color = OrpheusColors.ninersRed,
        expandedTitle = "Drum Pads",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        // Center the whole content to keep everything aligned and closer together
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Header Row for common parameters
                HeaderRow()

                Spacer(Modifier.height(4.dp))

                // BD Row (Red) - I key
                DrumRow(
                    label = "BD",
                    keyHint = "I",
                    color = OrpheusColors.ninersRed,
                    frequency = state.bdFrequency,
                    tone = state.bdTone,
                    decay = state.bdDecay,
                    p4 = state.bdP4,
                    p4Label = "AFM",
                    isActive = state.isBdActive,
                    onFrequencyChange = actions.setBdFrequency,
                    onToneChange = actions.setBdTone,
                    onDecayChange = actions.setBdDecay,
                    onP4Change = actions.setBdP4,
                    onTriggerStart = actions.startBdTrigger,
                    onTriggerEnd = actions.stopBdTrigger
                )

                Spacer(Modifier.height(8.dp))

                // SD Row (Gold) - O key
                DrumRow(
                    label = "SD",
                    keyHint = "O",
                    color = OrpheusColors.ninersGold,
                    frequency = state.sdFrequency,
                    tone = state.sdTone,
                    decay = state.sdDecay,
                    p4 = state.sdP4,
                    p4Label = "SNAP",
                    isActive = state.isSdActive,
                    onFrequencyChange = actions.setSdFrequency,
                    onToneChange = actions.setSdTone,
                    onDecayChange = actions.setSdDecay,
                    onP4Change = actions.setSdP4,
                    onTriggerStart = actions.startSdTrigger,
                    onTriggerEnd = actions.stopSdTrigger
                )

                Spacer(Modifier.height(8.dp))

                // HH Row (White) - P key
                DrumRow(
                    label = "HH",
                    keyHint = "P",
                    color = Color.White,
                    frequency = state.hhFrequency,
                    tone = state.hhTone,
                    decay = state.hhDecay,
                    p4 = state.hhP4,
                    p4Label = "NOISE",
                    isActive = state.isHhActive,
                    onFrequencyChange = actions.setHhFrequency,
                    onToneChange = actions.setHhTone,
                    onDecayChange = actions.setHhDecay,
                    onP4Change = actions.setHhP4,
                    onTriggerStart = actions.startHhTrigger,
                    onTriggerEnd = actions.stopHhTrigger
                )
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp), // Align with columns
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabelHeader("TUNE", Modifier.weight(1f))
        LabelHeader("TONE", Modifier.weight(1f))
        LabelHeader("DECAY", Modifier.weight(1f))
        LabelHeader("MOD", Modifier.weight(1f))
        LabelHeader("TRIG", Modifier.weight(1f))
    }
}

@Composable
private fun LabelHeader(text: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrumRow(
    label: String,
    keyHint: String,
    color: Color,
    frequency: Float,
    tone: Float,
    decay: Float,
    p4: Float,
    p4Label: String,
    isActive: Boolean,
    onFrequencyChange: (Float) -> Unit,
    onToneChange: (Float) -> Unit,
    onDecayChange: (Float) -> Unit,
    onP4Change: (Float) -> Unit,
    onTriggerStart: () -> Unit,
    onTriggerEnd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label with key hint
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Text("[$keyHint]", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.5f))
        }

        // Parameter + Trigger Columns
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KnobGroup(frequency, onFrequencyChange, color, Modifier.weight(1f))
            KnobGroup(tone, onToneChange, color, Modifier.weight(1f))
            KnobGroup(decay, onDecayChange, color, Modifier.weight(1f))
            KnobGroup(p4, onP4Change, color, Modifier.weight(1f), p4Label)

            // Trigger Pad aligned as a 5th column
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PulseButton(
                    size = 32.dp,
                    label = "",
                    isActive = isActive,
                    onPulseStart = onTriggerStart,
                    onPulseEnd = onTriggerEnd,
                    activeColor = color
                )
                // Spacer matches the possible label under MOD knob to keep button centered
                if (p4Label != "MOD") {
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun KnobGroup(
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    bottomLabel: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RotaryKnob(
            value = value,
            onValueChange = onValueChange,
            size = 32.dp,
            progressColor = color
        )
    }
}

@Preview
@Composable
fun DrumEnginePanelPreview() {
    OrpheusTheme {
        DrumsPanel(
            drumFeature = DrumViewModel.previewFeature(),
            isExpanded = true,
        )
    }
}

