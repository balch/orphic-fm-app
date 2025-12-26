package org.balch.orpheus.ui.mobile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob

/**
 * Compact Landscape Layout: Designed for mobile landscape orientation.
 * Displays 8 voices in a 2x4 grid with top-bar controls for Pitch and Hold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeLayout(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(12.dp)

    // Preset dropdown state
    var presetDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp)
    ) {
        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quad 1 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches[0],
                onValueChange = { voiceViewModel.onQuadPitchChange(0, it) },
                label = "PITCH 1-4",
                controlId = ControlIds.quadPitch(0),
                size = 36.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            // Quad 1 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds[0],
                onValueChange = { voiceViewModel.onQuadHoldChange(0, it) },
                label = "HOLD 1-4",
                controlId = ControlIds.quadHold(0),
                size = 36.dp,
                progressColor = OrpheusColors.warmGlow
            )

            Spacer(Modifier.width(16.dp))

            // Quad 2 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches[1],
                onValueChange = { voiceViewModel.onQuadPitchChange(1, it) },
                label = "PITCH 5-8",
                controlId = ControlIds.quadPitch(1),
                size = 36.dp,
                progressColor = OrpheusColors.synthGreen
            )
            // Quad 2 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds[1],
                onValueChange = { voiceViewModel.onQuadHoldChange(1, it) },
                label = "HOLD 5-8",
                controlId = ControlIds.quadHold(1),
                size = 36.dp,
                progressColor = OrpheusColors.warmGlow
            )

            Spacer(Modifier.width(16.dp))

            // Patch Selector Dropdown
            ExposedDropdownMenuBox(
                expanded = presetDropdownExpanded,
                onExpandedChange = { presetDropdownExpanded = it }
            ) {
                TextField(
                    value = presetState.selectedPreset?.name ?: "Select Patch",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .width(150.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = presetDropdownExpanded,
                    onDismissRequest = { presetDropdownExpanded = false }
                ) {
                    presetState.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                presetViewModel.applyPreset(preset)
                                presetDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Voice Grid: 2 rows of 4 voices
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 4) {
                CompactVoiceButton(
                    voiceIndex = i,
                    isActive = voiceState.voiceStates[i].pulse,
                    isHolding = voiceState.voiceStates[i].isHolding,
                    onPulseStart = { voiceViewModel.onPulseStart(i) },
                    onPulseEnd = { voiceViewModel.onPulseEnd(i) },
                    onHoldChange = { voiceViewModel.onHoldChange(i, it) },
                    color = OrpheusColors.neonMagenta
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 4 until 8) {
                CompactVoiceButton(
                    voiceIndex = i,
                    isActive = voiceState.voiceStates[i].pulse,
                    isHolding = voiceState.voiceStates[i].isHolding,
                    onPulseStart = { voiceViewModel.onPulseStart(i) },
                    onPulseEnd = { voiceViewModel.onPulseEnd(i) },
                    onHoldChange = { voiceViewModel.onHoldChange(i, it) },
                    color = OrpheusColors.synthGreen
                )
            }
        }
    }
}

/**
 * A compact, touch-friendly voice button for mobile layouts.
 */
@Composable
private fun CompactVoiceButton(
    voiceIndex: Int,
    isActive: Boolean,
    isHolding: Boolean,
    onPulseStart: () -> Unit,
    onPulseEnd: () -> Unit,
    onHoldChange: (Boolean) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val baseColor = if (isHolding) color else color.copy(alpha = 0.6f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Voice number label
        Text(
            text = "${voiceIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        // Pulse button
        PulseButton(
            size = 56.dp,
            label = "",
            isActive = isActive,
            onPulseStart = onPulseStart,
            onPulseEnd = onPulseEnd,
            isLearnMode = false, // Simplified for compact view
            isLearning = false,
            onLearnSelect = {}
        )
        // Hold toggle (tap on voice number to toggle for simplicity)
        Box(
            modifier = Modifier
                .border(1.dp, baseColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isHolding) "HOLD" else "tap",
                style = MaterialTheme.typography.labelSmall,
                color = baseColor,
                modifier = Modifier
                    .align(Alignment.Center)
            )
        }
    }
}
