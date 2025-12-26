package org.balch.orpheus.ui.compact

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.compact.widgets.CompactVoicePanel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

/**
 * Compact Landscape Layout: Instrument-style design for mobile landscape.
 * 
 * Layout:
 * - Top: Patch and Viz dropdowns
 * - Left: Quad 1 (voices 1-2 top, 3-4 bottom)
 * - Center: Quad knobs (Pitch/Hold for each quad)
 * - Right: Quad 2 (voices 5-6 top, 7-8 bottom)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeLayout(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
    vizViewModel: VizViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val vizState by vizViewModel.uiState.collectAsState()
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(12.dp)

    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Dropdown states
    var presetDropdownExpanded by remember { mutableStateOf(false) }
    var vizDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    voiceState = voiceState,
                    voiceViewModel = voiceViewModel,
                    isDialogActive = presetDropdownExpanded || vizDropdownExpanded
                )
            }
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(4.dp)
    ) {
        // Top bar: Patch and Viz dropdowns
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Patch dropdown
            ExposedDropdownMenuBox(
                expanded = presetDropdownExpanded,
                onExpandedChange = { presetDropdownExpanded = it }
            ) {
                TextField(
                    value = presetState.selectedPreset?.name ?: "Patch",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded) },
                    modifier = Modifier.menuAnchor().width(140.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = presetDropdownExpanded,
                    onDismissRequest = { presetDropdownExpanded = false }
                ) {
                    presetState.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                presetViewModel.applyPreset(preset)
                                presetDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Spacer
            Spacer(Modifier.width(16.dp))

            // Viz dropdown
            ExposedDropdownMenuBox(
                expanded = vizDropdownExpanded,
                onExpandedChange = { vizDropdownExpanded = it }
            ) {
                TextField(
                    value = vizState.selectedViz.name,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vizDropdownExpanded) },
                    modifier = Modifier.menuAnchor().width(140.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = vizDropdownExpanded,
                    onDismissRequest = { vizDropdownExpanded = false }
                ) {
                    vizState.visualizations.forEach { viz ->
                        DropdownMenuItem(
                            text = { Text(viz.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                vizViewModel.selectVisualization(viz)
                                vizDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Main content: Left Quad with Knobs | Right Quad with Knobs
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Quad 1 voices + Quad 1 knobs
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quad 1 voices (2x2 grid)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Top pair: voices 1-2
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactVoicePanel(
                            voiceIndex = 0,
                            tune = voiceState.voiceStates[0].tune,
                            envelopeSpeed = voiceState.voiceEnvelopeSpeeds[0],
                            isActive = voiceState.voiceStates[0].pulse,
                            isHolding = voiceState.voiceStates[0].isHolding,
                            onTuneChange = { voiceViewModel.onVoiceTuneChange(0, it) },
                            onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(0, it) },
                            onPulseStart = { voiceViewModel.onPulseStart(0) },
                            onPulseEnd = { voiceViewModel.onPulseEnd(0) },
                            onHoldChange = { voiceViewModel.onHoldChange(0, it) },
                            color = OrpheusColors.neonMagenta
                        )
                        CompactVoicePanel(
                            voiceIndex = 1,
                            tune = voiceState.voiceStates[1].tune,
                            envelopeSpeed = voiceState.voiceEnvelopeSpeeds[1],
                            isActive = voiceState.voiceStates[1].pulse,
                            isHolding = voiceState.voiceStates[1].isHolding,
                            onTuneChange = { voiceViewModel.onVoiceTuneChange(1, it) },
                            onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(1, it) },
                            onPulseStart = { voiceViewModel.onPulseStart(1) },
                            onPulseEnd = { voiceViewModel.onPulseEnd(1) },
                            onHoldChange = { voiceViewModel.onHoldChange(1, it) },
                            color = OrpheusColors.neonMagenta
                        )
                    }
                    // Bottom pair: voices 3-4
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactVoicePanel(
                            voiceIndex = 2,
                            tune = voiceState.voiceStates[2].tune,
                            envelopeSpeed = voiceState.voiceEnvelopeSpeeds[2],
                            isActive = voiceState.voiceStates[2].pulse,
                            isHolding = voiceState.voiceStates[2].isHolding,
                            onTuneChange = { voiceViewModel.onVoiceTuneChange(2, it) },
                            onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(2, it) },
                            onPulseStart = { voiceViewModel.onPulseStart(2) },
                            onPulseEnd = { voiceViewModel.onPulseEnd(2) },
                            onHoldChange = { voiceViewModel.onHoldChange(2, it) },
                            color = OrpheusColors.neonMagenta
                        )
                        CompactVoicePanel(
                            voiceIndex = 3,
                            tune = voiceState.voiceStates[3].tune,
                            envelopeSpeed = voiceState.voiceEnvelopeSpeeds[3],
                            isActive = voiceState.voiceStates[3].pulse,
                            isHolding = voiceState.voiceStates[3].isHolding,
                            onTuneChange = { voiceViewModel.onVoiceTuneChange(3, it) },
                            onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(3, it) },
                            onPulseStart = { voiceViewModel.onPulseStart(3) },
                            onPulseEnd = { voiceViewModel.onPulseEnd(3) },
                            onHoldChange = { voiceViewModel.onHoldChange(3, it) },
                            color = OrpheusColors.neonMagenta
                        )
                    }
                }

                // Quad 1 knobs (vertically stacked)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RotaryKnob(
                        value = voiceState.quadGroupPitches[0],
                        onValueChange = { voiceViewModel.onQuadPitchChange(0, it) },
                        label = "P1-4",
                        controlId = ControlIds.quadPitch(0),
                        size = 40.dp,
                        progressColor = OrpheusColors.neonMagenta
                    )
                    RotaryKnob(
                        value = voiceState.quadGroupHolds[0],
                        onValueChange = { voiceViewModel.onQuadHoldChange(0, it) },
                        label = "H1-4",
                        controlId = ControlIds.quadHold(0),
                        size = 40.dp,
                        progressColor = OrpheusColors.warmGlow
                    )
                }
            }

            // Right side: Quad 2 knobs + Quad 2 voices
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quad 2 knobs (vertically stacked)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RotaryKnob(
                        value = voiceState.quadGroupPitches[1],
                        onValueChange = { voiceViewModel.onQuadPitchChange(1, it) },
                        label = "P5-8",
                        controlId = ControlIds.quadPitch(1),
                        size = 40.dp,
                        progressColor = OrpheusColors.synthGreen
                    )
                    RotaryKnob(
                        value = voiceState.quadGroupHolds[1],
                        onValueChange = { voiceViewModel.onQuadHoldChange(1, it) },
                        label = "H5-8",
                        controlId = ControlIds.quadHold(1),
                        size = 40.dp,
                        progressColor = OrpheusColors.warmGlow
                    )
                }

                // Quad 2 voices (2x2 grid)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                // Top pair: voices 5-6
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactVoicePanel(
                        voiceIndex = 4,
                        tune = voiceState.voiceStates[4].tune,
                        envelopeSpeed = voiceState.voiceEnvelopeSpeeds[4],
                        isActive = voiceState.voiceStates[4].pulse,
                        isHolding = voiceState.voiceStates[4].isHolding,
                        onTuneChange = { voiceViewModel.onVoiceTuneChange(4, it) },
                        onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(4, it) },
                        onPulseStart = { voiceViewModel.onPulseStart(4) },
                        onPulseEnd = { voiceViewModel.onPulseEnd(4) },
                        onHoldChange = { voiceViewModel.onHoldChange(4, it) },
                        color = OrpheusColors.synthGreen
                    )
                    CompactVoicePanel(
                        voiceIndex = 5,
                        tune = voiceState.voiceStates[5].tune,
                        envelopeSpeed = voiceState.voiceEnvelopeSpeeds[5],
                        isActive = voiceState.voiceStates[5].pulse,
                        isHolding = voiceState.voiceStates[5].isHolding,
                        onTuneChange = { voiceViewModel.onVoiceTuneChange(5, it) },
                        onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(5, it) },
                        onPulseStart = { voiceViewModel.onPulseStart(5) },
                        onPulseEnd = { voiceViewModel.onPulseEnd(5) },
                        onHoldChange = { voiceViewModel.onHoldChange(5, it) },
                        color = OrpheusColors.synthGreen
                    )
                }
                // Bottom pair: voices 7-8
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactVoicePanel(
                        voiceIndex = 6,
                        tune = voiceState.voiceStates[6].tune,
                        envelopeSpeed = voiceState.voiceEnvelopeSpeeds[6],
                        isActive = voiceState.voiceStates[6].pulse,
                        isHolding = voiceState.voiceStates[6].isHolding,
                        onTuneChange = { voiceViewModel.onVoiceTuneChange(6, it) },
                        onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(6, it) },
                        onPulseStart = { voiceViewModel.onPulseStart(6) },
                        onPulseEnd = { voiceViewModel.onPulseEnd(6) },
                        onHoldChange = { voiceViewModel.onHoldChange(6, it) },
                        color = OrpheusColors.synthGreen
                    )
                    CompactVoicePanel(
                        voiceIndex = 7,
                        tune = voiceState.voiceStates[7].tune,
                        envelopeSpeed = voiceState.voiceEnvelopeSpeeds[7],
                        isActive = voiceState.voiceStates[7].pulse,
                        isHolding = voiceState.voiceStates[7].isHolding,
                        onTuneChange = { voiceViewModel.onVoiceTuneChange(7, it) },
                        onEnvelopeSpeedChange = { voiceViewModel.onVoiceEnvelopeSpeedChange(7, it) },
                        onPulseStart = { voiceViewModel.onPulseStart(7) },
                        onPulseEnd = { voiceViewModel.onPulseEnd(7) },
                        onHoldChange = { voiceViewModel.onHoldChange(7, it) },
                        color = OrpheusColors.synthGreen
                    )
                }
                }
            }
        }
    }
}


// ==================== PREVIEWS ====================

@Preview(widthDp = 800, heightDp = 400)
@Composable
private fun CompactLandscapeLayoutPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
    }
}
