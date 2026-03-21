package org.balch.orpheus.features.bass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.BassEngine
import org.balch.orpheus.core.audio.BassScale
import org.balch.orpheus.core.audio.ClockDivision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.RotaryKnob

// Amber/orange palette for bass — warm, earthy, distinct from warps green and echo lavender
private data class BassColors(
    val panelColor: Color = OrpheusColors.bassAmber,
    val knobTrackColor: Color = OrpheusColors.bassDarkAmber,
    val knobProgressColor: Color = OrpheusColors.bassAmber,
    val knobColor: Color = OrpheusColors.bassKnobCap,
    val labelColor: Color = OrpheusColors.bassAmber,
)

// MIDI note number to name (C-1 = 0, C2 = 36 default)
private fun midiNoteToName(noteNumber: Int): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = (noteNumber / 12) - 1
    val name = noteNames[noteNumber % 12]
    return "$name$octave"
}

// Bass-friendly root notes: C1-B2 (MIDI 24-47), covering the bass sweet spot
private val bassRootNotes = (24..47).toList()

@Composable
fun BassPanel(
    feature: BassFeature = BassViewModel.feature(),
    outVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val bassColors = remember { BassColors() }

    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions
    val outViz by outVizFlow.collectAsState()

    CollapsibleColumnPanel(
        title = "BASS",
        color = bassColors.panelColor,
        expandedTitle = "Bassline",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        backgroundContent = {
            SignalTrace(data = outViz, color = bassColors.panelColor.copy(alpha = 0.4f))
        },
    ) {
        // ── Row 1: Source & Sequencer ─────────────────────────────────
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Engine dropdown
            EnumDropdown(
                label = "ENGINE",
                selectedDisplay = state.engine.displayName,
                entries = BassEngine.entries,
                displayName = { it.displayName },
                onSelected = actions.setEngine,
                color = bassColors.panelColor,
            )

            // Scale dropdown
            EnumDropdown(
                label = "SCALE",
                selectedDisplay = state.scale.displayName,
                entries = BassScale.entries,
                displayName = { it.displayName },
                onSelected = actions.setScale,
                color = bassColors.panelColor,
            )

            // Root note dropdown — bass-friendly notes across 2 octaves
            EnumDropdown(
                label = "ROOT",
                selectedDisplay = midiNoteToName(state.rootNote),
                entries = bassRootNotes,
                displayName = { midiNoteToName(it) },
                onSelected = { actions.setRootNote(it) },
                color = bassColors.panelColor,
            )
        }

        // ── Row 2: Clock & Sequencer Controls ────────────────────────
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Clock division dropdown
            EnumDropdown(
                label = "CLOCK",
                selectedDisplay = state.clockDivision.displayName,
                entries = ClockDivision.entries,
                displayName = { it.displayName },
                onSelected = actions.setClockDivision,
                color = bassColors.panelColor,
            )

            // Step count segmented selector
            StepCountSelector(
                stepCount = state.stepCount,
                onStepCountChange = actions.setStepCount,
                color = bassColors.panelColor,
            )

            // Mutation knob
            RotaryKnob(
                value = state.mutation,
                onValueChange = actions.setMutation,
                label = "MUTATE",
                size = 40.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_mutation",
            )
            RotaryKnob(
                value = state.lfoMix,
                onValueChange = actions.setLfoMix,
                label = "LFO",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_lfo_mix",
            )
        }

        // ── Row 3: Sound + Output ──────────────────────────────────────
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RotaryKnob(
                value = state.cutoff,
                onValueChange = actions.setCutoff,
                label = "CUTOFF",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_cutoff",
            )
            RotaryKnob(
                value = state.resonance,
                onValueChange = actions.setResonance,
                label = "RESO",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_resonance",
            )
            RotaryKnob(
                value = state.envelope,
                onValueChange = actions.setEnvelope,
                label = "ENV",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_envelope",
            )
            RotaryKnob(
                value = state.overdrive,
                onValueChange = actions.setOverdrive,
                label = "DRIVE",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_overdrive",
            )
            RotaryKnob(
                value = state.compressor,
                onValueChange = actions.setCompressor,
                label = "COMP",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_compressor",
            )
            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                size = 38.dp,
                trackColor = bassColors.knobTrackColor,
                progressColor = bassColors.knobProgressColor,
                knobColor = bassColors.knobColor,
                labelColor = bassColors.labelColor,
                controlId = "bass_mix",
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared private widgets
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Generic compact dropdown for any enum type with a displayName.
 */
@Composable
private fun <T> EnumDropdown(
    label: String,
    selectedDisplay: String,
    entries: List<T>,
    displayName: (T) -> String,
    onSelected: (T) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Spacer(Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .clickable { expanded = true }
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedDisplay,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select $label",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(OrpheusColors.panelSurface),
            ) {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = displayName(entry),
                                color = if (displayName(entry) == selectedDisplay) color else Color.White,
                            )
                        },
                        onClick = {
                            onSelected(entry)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Compact segmented selector for the four step-count presets: 4, 8, 12, 16.
 */
@Composable
private fun StepCountSelector(
    stepCount: Int,
    onStepCountChange: (Int) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(4, 8, 12, 16)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = "STEPS",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(2.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { preset ->
                val isSelected = stepCount == preset
                Box(
                    modifier = Modifier
                        .clickable { onStepCountChange(preset) }
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isSelected) color.copy(alpha = 0.85f)
                            else OrpheusColors.darkVoid.copy(alpha = 0.6f)
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.toString(),
                        color = if (isSelected) OrpheusColors.darkVoid else color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Bass Panel — Expanded Default", widthDp = 600, heightDp = 500)
@Composable
fun BassPanelPreview() {
    OrpheusTheme {
        BassPanel(
            feature = BassViewModel.previewFeature(BassUiState()),
            isExpanded = true,
        )
    }
}

@Preview(name = "Bass Panel — Active State", widthDp = 600, heightDp = 500)
@Composable
fun BassPanelActivePreview() {
    OrpheusTheme {
        BassPanel(
            feature = BassViewModel.previewFeature(
                BassUiState(
                    engine = BassEngine.FM,
                    rootNote = 33,
                    scale = BassScale.DORIAN,
                    clockDivision = ClockDivision.X2,
                    stepCount = 8,
                    mutation = 0.4f,
                    cutoff = 0.65f,
                    resonance = 0.3f,
                    envelope = 0.8f,
                    overdrive = 0.2f,
                    compressor = 0.5f,
                    mix = 0.9f,
                    lfoMix = 0.35f,
                )
            ),
            isExpanded = true,
        )
    }
}
