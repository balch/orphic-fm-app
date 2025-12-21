package org.balch.songe.ui.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.theme.SongeColors

/**
 * Collapsible settings panel for the left side of top row.
 * Shows a right arrow when collapsed, expands to show settings categories.
 */
@Composable
fun SettingsPanel(
    midiDeviceName: String?,
    isMidiOpen: Boolean,
    isLearnModeActive: Boolean,
    onMidiClick: () -> Unit,
    onLearnToggle: () -> Unit,
    onLearnSave: () -> Unit,
    onLearnCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    // Auto-expand when learn mode is active
    if (isLearnModeActive && !isExpanded) {
        isExpanded = true
    }
    
    val targetWidth by animateDpAsState(
        targetValue = if (isExpanded) 140.dp else 32.dp,
        animationSpec = tween(durationMillis = 200)
    )
    
    Box(
        modifier = modifier
            .width(targetWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.darkVoid.copy(alpha = 0.6f),
                        SongeColors.darkVoid.copy(alpha = 0.9f)
                    )
                )
            )
            .border(
                width = if (isLearnModeActive) 2.dp else 1.dp,
                color = if (isLearnModeActive) SongeColors.neonMagenta.copy(alpha = 0.7f) 
                       else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .animateContentSize()
    ) {
        if (isExpanded) {
            // Expanded content
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Collapse arrow (now points left)
                Row(
                    modifier = Modifier
                        .clickable { if (!isLearnModeActive) isExpanded = false }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "◀",
                        fontSize = 10.sp,
                        color = if (isLearnModeActive) SongeColors.neonMagenta.copy(alpha = 0.6f)
                               else Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isLearnModeActive) "LEARNING" else "SETTINGS",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLearnModeActive) SongeColors.neonMagenta
                               else Color.White.copy(alpha = 0.5f)
                    )
                }
                
                // MIDI Section with integrated Learn button
                MidiSettingSection(
                    deviceName = midiDeviceName,
                    isOpen = isMidiOpen,
                    isLearnModeActive = isLearnModeActive,
                    onMidiClick = onMidiClick,
                    onLearnToggle = onLearnToggle,
                    onLearnSave = onLearnSave,
                    onLearnCancel = onLearnCancel
                )
            }
        } else {
            // Collapsed - just the expand arrow
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(32.dp)
                    .clickable { isExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▶",
                    fontSize = 12.sp,
                    color = SongeColors.neonCyan.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MidiSettingSection(
    deviceName: String?,
    isOpen: Boolean,
    isLearnModeActive: Boolean,
    onMidiClick: () -> Unit,
    onLearnToggle: () -> Unit,
    onLearnSave: () -> Unit,
    onLearnCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A2A))
            .border(
                1.dp, 
                if (isOpen) SongeColors.synthGreen.copy(alpha = 0.5f) else Color.Transparent, 
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Device info row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMidiClick),
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "MIDI",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                // Extract friendly device name (e.g., "S-1" from "CoreMIDI4J - S-1")
                val friendlyName = deviceName?.let { name ->
                    if (name.contains(" - ")) {
                        name.substringAfter(" - ")
                    } else {
                        name.take(15)
                    }
                }
                
                if (isOpen && friendlyName != null) {
                    // Connected state: "S-1: Connected" in green
                    Text(
                        "$friendlyName: Connected",
                        fontSize = 7.sp,
                        color = SongeColors.synthGreen,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                } else if (friendlyName != null) {
                    // Known device but not connected
                    Text(
                        "$friendlyName: Not Connected",
                        fontSize = 7.sp,
                        color = Color.Red.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                } else {
                    // No device known
                    Text(
                        "Not Connected",
                        fontSize = 7.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
        }
        
        // Learn mode controls
        if (isLearnModeActive) {
            // Save/Cancel row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Cancel button (Red X)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF8B0000).copy(alpha = 0.3f))
                        .border(1.dp, Color.Red.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onLearnCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "✗",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
                
                // Save button (Green Check)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SongeColors.synthGreen.copy(alpha = 0.2f))
                        .border(1.dp, SongeColors.synthGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onLearnSave),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SongeColors.synthGreen
                    )
                }
            }
            
            // Instructions
            Text(
                "Click a control, then MIDI key",
                fontSize = 6.sp,
                color = SongeColors.neonMagenta.copy(alpha = 0.7f),
                maxLines = 1
            )
        } else {
            // Learn button (small, disabled if no MIDI)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isOpen) SongeColors.neonMagenta.copy(alpha = 0.15f)
                        else Color.Gray.copy(alpha = 0.1f)
                    )
                    .then(
                        if (isOpen) Modifier.clickable(onClick = onLearnToggle)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "LEARN",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOpen) SongeColors.neonMagenta else Color.Gray.copy(alpha = 0.4f)
                )
            }
        }
    }
}
