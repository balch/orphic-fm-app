package org.balch.orpheus.features.pulsar

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.EnginePickerButton
import org.balch.orpheus.ui.widgets.RotaryKnob

// Display order (decoupled from C++ kPulsarScenes[] index)
private val KitEntries = listOf(
    "COSMIC TECHNO" to 2,
    "DOG HOUSE" to 3,
    "CHILLWAVE" to 1,
    "ARTEMIS II" to 4,
    "DEEP SPACE" to 0,
)
private val NoteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val ScaleNames = listOf("Minor", "Major", "Pentatonic", "Phrygian", "Whole Tone", "Chromatic")

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
 * Pulsar Beat Machine panel.
 *
 * Layout:
 * 1. Header row: Kit cycle button + Root dropdown + Scale dropdown + Mix knob + BPM knob
 * 2. Step grid (tappable for track selection)
 * 3. Voice detail strip (only when track selected)
 * 4. Macro knobs: ENERGY, COMPLEXITY, SPACE, MOOD, DELAY, REVERB
 */
@Composable
fun PulsarPanel(
    pulsar: PulsarFeature,
    vizFlow: StateFlow<PulsarVizData> = MutableStateFlow(PulsarVizData()),
    trackVizFlows: List<StateFlow<FloatArray>> = List(8) { MutableStateFlow(FloatArray(0)) },
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val vizData by vizFlow.collectAsState()
    CollapsibleColumnPanel(
        modifier = modifier,
        title = "PULSE",
        color = OrpheusColors.cosmicPurple,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        expandedTitle = "8 Track",
        showCollapsedHeader = showCollapsedHeader,
    ) {
        val state by pulsar.stateFlow.collectAsState()
        val actions = pulsar.actions

        // Row 1: Selectors only
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            EnumDropdown(
                label = "KIT",
                selectedDisplay = KitEntries.first { it.second == state.sceneIndex }.first,
                entries = KitEntries,
                displayName = { it.first },
                onSelected = { actions.setScene(it.second) },
                color = OrpheusColors.cosmicPurple,
            )

            EnumDropdown(
                label = "ROOT",
                selectedDisplay = NoteNames[state.rootNote],
                entries = NoteNames.indices.toList(),
                displayName = { NoteNames[it] },
                onSelected = actions.setRootNote,
                color = OrpheusColors.cosmicPurple,
            )

            EnumDropdown(
                label = "SCALE",
                selectedDisplay = ScaleNames[state.scaleIndex],
                entries = ScaleNames.indices.toList(),
                displayName = { ScaleNames[it] },
                onSelected = actions.setScale,
                color = OrpheusColors.cosmicPurple,
            )

            // Envelope mode toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "ENV",
                    style = MaterialTheme.typography.labelSmall,
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clickable { actions.setEnvelopeMode((state.envelopeMode + 1) % 3) }
                        .clip(RoundedCornerShape(6.dp))
                        .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        modifier = Modifier.widthIn(min = 40.dp),
                        text = when (state.envelopeMode) {
                            1 -> "WAVES"
                            2 -> "BLEND"
                            else -> "AD"
                        },
                        color = OrpheusColors.cosmicPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Row 2: Knobs flanking the step grid
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RotaryKnob(
                    value = state.bpm,
                    onValueChange = actions.setBpm,
                    label = "BPM",
                    controlId = PulsarSymbol.BPM.controlId.key,
                    range = 40f..300f,
                    size = 28.dp,
                    progressColor = OrpheusColors.cosmicPurple,
                    valueFormatter = { "${it.toInt()}" },
                )

                RotaryKnob(
                    value = state.reverbSend,
                    onValueChange = actions.setReverbSend,
                    label = "REVERB",
                    controlId = PulsarSymbol.REVERB_SEND.controlId.key,
                    size = 28.dp,
                    progressColor = OrpheusColors.cosmicPurple,
                )
            }

            // Center: Step grid — weight(1f) fills remaining Row space, max capped inside
            PulsarStepGrid(
                vizData = vizData,
                trackVizFlows = trackVizFlows,
                energy = state.energy,
                space = state.space,
                complexity = state.complexity,
                mood = state.mood,
                selectedTrack = state.selectedTrack,
                onTrackSelected = actions.selectTrack,
                modifier = Modifier,
            )
        }

        // Row 3: Voice detail strip (only when a track is selected)
        // Auto-dismiss after 10s of inactivity, suppressed while picker is open
        val selected = state.selectedTrack
        if (selected != null) {
            var pickerOpen by remember { mutableStateOf(false) }
            val edmEngine = state.trackEnginesEdm[selected]
            val spaceEngine = state.trackEnginesSpace[selected]
            LaunchedEffect(selected, edmEngine, spaceEngine, pickerOpen) {
                if (!pickerOpen) {
                    delay(10_000L)
                    actions.selectTrack(null)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = PULSAR_TRACK_NAMES[selected],
                    color = TrackColors[selected],
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )

                Text(
                    text = "HI",
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                )
                EnginePickerButton(
                    currentEngine = edmEngine,
                    onEngineChange = { actions.setTrackEngineEdm(selected, it) },
                    color = OrpheusColors.cosmicPurple,
                    label = pulsarEngineLabel(edmEngine),
                    config = PULSAR_TRACK_PICKERS[selected],
                    v2Config = PULSAR_V2_PICKER,
                    size = 36.dp,
                    onExpandedChange = { pickerOpen = it },
                )

                Text(
                    text = "LO",
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                )
                EnginePickerButton(
                    currentEngine = spaceEngine,
                    onEngineChange = { actions.setTrackEngineSpace(selected, it) },
                    color = OrpheusColors.cosmicPurple,
                    label = pulsarEngineLabel(spaceEngine),
                    config = PULSAR_TRACK_PICKERS[selected],
                    v2Config = PULSAR_V2_PICKER,
                    size = 36.dp,
                    onExpandedChange = { pickerOpen = it },
                )
            }
        }

        // Row 4: Big macro knobs
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RotaryKnob(
                value = state.energy,
                onValueChange = actions.setEnergy,
                label = "ENERGY",
                controlId = PulsarSymbol.ENERGY.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )
            RotaryKnob(
                value = state.complexity,
                onValueChange = actions.setComplexity,
                label = "COMPLEXITY",
                controlId = PulsarSymbol.COMPLEXITY.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )
            RotaryKnob(
                value = state.space,
                onValueChange = actions.setSpace,
                label = "SPACE",
                controlId = PulsarSymbol.SPACE.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )
            RotaryKnob(
                value = state.mood,
                onValueChange = actions.setMood,
                label = "MOOD",
                controlId = PulsarSymbol.MOOD.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )
            RotaryKnob(
                value = state.percMix,
                onValueChange = actions.setPercMix,
                label = "PERC",
                controlId = PulsarSymbol.PERC_MIX.controlId.key,
                size = 32.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )
            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = PulsarSymbol.MIX.controlId.key,
                size = 32.dp,
                progressColor = OrpheusColors.cosmicPurple,
            )

        }

        // (small knobs now flank the grid above)
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 300)
@Composable
private fun PulsarPanelPreview() {
    PulsarPanel(
        pulsar = PulsarViewModel.previewFeature(),
        isExpanded = true,
        showCollapsedHeader = false,
    )
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 380)
@Composable
private fun PulsarPanelWithSelectionPreview() {
    PulsarPanel(
        pulsar = PulsarViewModel.previewFeature(
            PulsarUiState(selectedTrack = 2, mix = 0.8f)
        ),
        isExpanded = true,
        showCollapsedHeader = false,
    )
}
