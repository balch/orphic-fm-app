package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.theme.SongeColors

/**
 * MIDI management properties
 */
data class MidiProps(
    val deviceName: String?,
    val isOpen: Boolean,
    val isLearnModeActive: Boolean,
    val onClick: () -> Unit,
    val onLearnToggle: () -> Unit,
    val onLearnSave: () -> Unit,
    val onLearnCancel: () -> Unit
)

@Composable
fun MidiPanel(
    midiProps: MidiProps,
    modifier: Modifier = Modifier
) {
     // Auto-expand if learning logic is needed? Or let user expand.
     // For now, simple independent panel.
    CollapsibleColumnPanel(
        title = "MIDI",
        color = SongeColors.synthGreen,
        initialExpanded = false, // Collapsed initially
        modifier = modifier
    ) {
        MidiSettingSectionContent(
            deviceName = midiProps.deviceName,
            isOpen = midiProps.isOpen,
            isLearnModeActive = midiProps.isLearnModeActive,
            onMidiClick = midiProps.onClick,
            onLearnToggle = midiProps.onLearnToggle,
            onLearnSave = midiProps.onLearnSave,
            onLearnCancel = midiProps.onLearnCancel
        )
    }
}

@Composable
private fun MidiSettingSectionContent(
    deviceName: String?,
    isOpen: Boolean,
    isLearnModeActive: Boolean,
    onMidiClick: () -> Unit,
    onLearnToggle: () -> Unit,
    onLearnSave: () -> Unit,
    onLearnCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Device info
        Column {
            Text(
                "DEVICE",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.6f)
            )
            val friendlyName = deviceName?.substringAfter(" - ") ?: deviceName ?: "No Device"
            Text(
                if (isOpen) "$friendlyName\nConnected" else "$friendlyName\nNot Connected",
                fontSize = 8.sp,
                color = if (isOpen) SongeColors.synthGreen else Color.Red.copy(alpha = 0.7f),
                lineHeight = 10.sp
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Learn controls
        if (isLearnModeActive) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color.Red.copy(alpha = 0.2f)).clickable(onClick = onLearnCancel),
                    contentAlignment = Alignment.Center
                ) { Text("✗", color = Color.Red) }
                
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(SongeColors.synthGreen.copy(alpha = 0.2f)).clickable(onClick = onLearnSave),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = SongeColors.synthGreen) }
            }
            Text("Select → Key", fontSize = 8.sp, color = SongeColors.neonMagenta)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOpen) SongeColors.neonMagenta.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.1f))
                    .clickable(enabled = isOpen, onClick = onLearnToggle),
                contentAlignment = Alignment.Center
            ) {
                Text("LEARN", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isOpen) SongeColors.neonMagenta else Color.Gray)
            }
        }
    }
}
