package org.balch.orpheus.features.flux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.triggers.DrumTriggerSource
import org.balch.orpheus.features.drum.DrumFeature
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

@Composable
fun TriggerRouterPanel(
    modifier: Modifier = Modifier,
    drumFeature: DrumFeature = DrumViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val drumState by drumFeature.stateFlow.collectAsState()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    
    val drumActions = drumFeature.actions
    val voiceActions = voiceFeature.actions

    // Using OrpheusColors.metallicBlue for a lighter look as requested
    CollapsibleColumnPanel(
        title = "TRIGG",
        color = OrpheusColors.metallicBlue, 
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedTitle = "Connect",
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(24.dp)
            ) {
                Spacer(Modifier.width(70.dp)) // Label + Preview space
                
                // X Headers (Pitch/CV)
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("X1", "X2", "X3").forEach { 
                        Text(it, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.warmGlow, fontSize = 10.sp) 
                    }
                }
                
                // Divider placeholder
                Spacer(Modifier.width(17.dp)) 

                // T Headers (Trigger)
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("T1", "T2", "T3").forEach { 
                        Text(it, style = MaterialTheme.typography.labelSmall, color = OrpheusColors.electricBlue, fontSize = 10.sp) 
                    }
                }
            }

            // BD
            TriggerMatrixRow(
                label = "BD",
                color = OrpheusColors.neonMagenta,
                isActive = drumState.isBdActive,
                selectedX = getDrumXSelection(drumState.bdPitchSource),
                selectedT = getDrumTSelection(drumState.bdTriggerSource),
                onXSelect = { handleDrumXSelect(it, drumState.bdPitchSource, drumActions.setBdPitchSource) },
                onTSelect = { handleDrumTSelect(it, drumState.bdTriggerSource, drumActions.setBdTriggerSource) }
            )

            // SD
            TriggerMatrixRow(
                label = "SD",
                color = OrpheusColors.neonMagenta,
                isActive = drumState.isSdActive,
                selectedX = getDrumXSelection(drumState.sdPitchSource),
                selectedT = getDrumTSelection(drumState.sdTriggerSource),
                onXSelect = { handleDrumXSelect(it, drumState.sdPitchSource, drumActions.setSdPitchSource) },
                onTSelect = { handleDrumTSelect(it, drumState.sdTriggerSource, drumActions.setSdTriggerSource) }
            )

            // HH
            TriggerMatrixRow(
                label = "HH",
                color = OrpheusColors.neonMagenta,
                isActive = drumState.isHhActive,
                selectedX = getDrumXSelection(drumState.hhPitchSource),
                selectedT = getDrumTSelection(drumState.hhTriggerSource),
                onXSelect = { handleDrumXSelect(it, drumState.hhPitchSource, drumActions.setHhPitchSource) },
                onTSelect = { handleDrumTSelect(it, drumState.hhTriggerSource, drumActions.setHhTriggerSource) }
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f))) // Separator
            
            // Voice Quads
            // Q1 (Voices 0..3)
            TriggerMatrixRow(
                label = "Q1",
                color = OrpheusColors.synthGreen,
                isActive = voiceState.voiceStates.take(4).any { it.pulse },
                selectedX = voiceState.quadPitchSources.getOrElse(0) { 0 },
                selectedT = voiceState.quadTriggerSources.getOrElse(0) { 0 },
                onXSelect = { idx -> 
                    val current = voiceState.quadPitchSources.getOrElse(0) { 0 }
                    voiceActions.setQuadPitchSource(0, if (current == idx) 0 else idx)
                },
                onTSelect = { idx ->
                    val current = voiceState.quadTriggerSources.getOrElse(0) { 0 }
                    voiceActions.setQuadTriggerSource(0, if (current == idx) 0 else idx)
                }
            )

            // Q2 (Voices 4..7)
            TriggerMatrixRow(
                label = "Q2",
                color = OrpheusColors.synthGreen,
                isActive = voiceState.voiceStates.drop(4).take(4).any { it.pulse },
                selectedX = voiceState.quadPitchSources.getOrElse(1) { 0 },
                selectedT = voiceState.quadTriggerSources.getOrElse(1) { 0 },
                onXSelect = { idx -> 
                    val current = voiceState.quadPitchSources.getOrElse(1) { 0 }
                    voiceActions.setQuadPitchSource(1, if (current == idx) 0 else idx)
                },
                onTSelect = { idx ->
                    val current = voiceState.quadTriggerSources.getOrElse(1) { 0 }
                    voiceActions.setQuadTriggerSource(1, if (current == idx) 0 else idx)
                }
            )

            // Q3 (Voices 8..11)
            TriggerMatrixRow(
                label = "Q3",
                color = OrpheusColors.synthGreen,
                isActive = voiceState.voiceStates.drop(8).take(4).any { it.pulse },
                selectedX = voiceState.quadPitchSources.getOrElse(2) { 0 },
                selectedT = voiceState.quadTriggerSources.getOrElse(2) { 0 },
                onXSelect = { idx -> 
                    val current = voiceState.quadPitchSources.getOrElse(2) { 0 }
                    voiceActions.setQuadPitchSource(2, if (current == idx) 0 else idx)
                },
                onTSelect = { idx ->
                    val current = voiceState.quadTriggerSources.getOrElse(2) { 0 }
                    voiceActions.setQuadTriggerSource(2, if (current == idx) 0 else idx)
                }
            )
        }
    }
}

// Helper to determine active X selection for drums (0=None, 1..3=X1..X3)
private fun getDrumXSelection(source: DrumTriggerSource): Int {
    return when (source) {
        DrumTriggerSource.FLUX_X1 -> 1
        DrumTriggerSource.FLUX_X2 -> 2
        DrumTriggerSource.FLUX_X3 -> 3
        else -> 0
    }
}

// Helper to determine active T selection for drums (0=None, 1..3=T1..T3)
private fun getDrumTSelection(source: DrumTriggerSource): Int {
    return when (source) {
        DrumTriggerSource.FLUX_T1 -> 1
        DrumTriggerSource.FLUX_T2 -> 2
        DrumTriggerSource.FLUX_T3 -> 3
        else -> 0
    }
}

private fun handleDrumXSelect(
    idx: Int, 
    currentSource: DrumTriggerSource, 
    action: (DrumTriggerSource) -> Unit
) {
    val currentX = getDrumXSelection(currentSource)
    if (currentX == idx) {
        action(DrumTriggerSource.INTERNAL)
    } else {
        val newSource = when(idx) {
            1 -> DrumTriggerSource.FLUX_X1
            2 -> DrumTriggerSource.FLUX_X2
            3 -> DrumTriggerSource.FLUX_X3
            else -> DrumTriggerSource.INTERNAL
        }
        action(newSource)
    }
}

private fun handleDrumTSelect(
    idx: Int, 
    currentSource: DrumTriggerSource, 
    action: (DrumTriggerSource) -> Unit
) {
    val currentT = getDrumTSelection(currentSource)
    if (currentT == idx) {
        action(DrumTriggerSource.INTERNAL)
    } else {
        val newSource = when(idx) {
            1 -> DrumTriggerSource.FLUX_T1
            2 -> DrumTriggerSource.FLUX_T2
            3 -> DrumTriggerSource.FLUX_T3
            else -> DrumTriggerSource.INTERNAL
        }
        action(newSource)
    }
}

@Composable
private fun TriggerMatrixRow(
    label: String,
    color: Color,
    isActive: Boolean,
    selectedX: Int?,
    selectedT: Int,
    onXSelect: ((Int) -> Unit)? = null,
    onTSelect: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(36.dp)
    ) {
        // Label + Preview
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(70.dp)
        ) {
            // Preview
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) color else Color.White.copy(alpha = 0.1f))
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }

        // X Columns (Pitch)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (selectedX != null && onXSelect != null) {
                for (i in 1..3) {
                    Connect4Cell(
                        selected = selectedX == i,
                        activeColor = OrpheusColors.warmGlow,
                        onClick = { onXSelect(i) }
                    )
                }
            } else {
                 for (i in 1..3) {
                    Connect4Cell(selected = false, activeColor = Color.Transparent, enabled = false)
                }
            }
        }

        // Segment Border
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.2f))
        )
        Spacer(Modifier.width(16.dp))

        // T Columns (Trigger)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 1..3) {
                Connect4Cell(
                    selected = selectedT == i,
                    activeColor = OrpheusColors.electricBlue,
                    onClick = { onTSelect(i) }
                )
            }
        }
    }
}

@Composable
private fun Connect4Cell(
    selected: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) activeColor else Color.Transparent)
            .border(
                width = 2.dp, 
                color = if (enabled) (if (selected) activeColor else Color.Gray) else Color.DarkGray.copy(alpha=0.3f), 
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Preview
@Composable
private fun TriggerRouterPanelPreview() {
    OrpheusTheme {
        TriggerRouterPanel(
            drumFeature = DrumViewModel.previewFeature(),
            voiceFeature = VoiceViewModel.previewFeature(),
            isExpanded = true,
            onExpandedChange = {}
        )
    }
}