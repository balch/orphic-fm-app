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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
    CollapsibleColumnPanel(
        title = "MIDI",
        color = SongeColors.synthGreen,
        initialExpanded = false,
        expandedWidth = 180.dp,
        useFlexWidth = true,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Text(
                text = "MIDI",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SongeColors.synthGreen
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Device status with connection dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Connection status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (midiProps.isOpen) SongeColors.synthGreen else Color.Red)
                )
                
                // Device name and status
                Column {
                    val friendlyName = midiProps.deviceName?.substringAfter(" - ") 
                        ?: midiProps.deviceName ?: "No Device"
                    Text(
                        text = friendlyName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (midiProps.isOpen) "Connected" else "Not Connected",
                        fontSize = 10.sp,
                        color = if (midiProps.isOpen) SongeColors.synthGreen else Color.Red.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Learn controls - directly under device info
            if (midiProps.isLearnModeActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Red.copy(alpha = 0.2f))
                            .clickable(onClick = midiProps.onLearnCancel),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text("CANCEL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Red) 
                    }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SongeColors.synthGreen.copy(alpha = 0.2f))
                            .clickable(onClick = midiProps.onLearnSave),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text("SAVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SongeColors.synthGreen) 
                    }
                }
                
                Text(
                    "Select control → Press key",
                    fontSize = 9.sp,
                    color = SongeColors.neonMagenta
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (midiProps.isOpen) SongeColors.neonMagenta.copy(alpha = 0.2f) 
                            else Color.Gray.copy(alpha = 0.1f)
                        )
                        .clickable(enabled = midiProps.isOpen, onClick = midiProps.onLearnToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "LEARN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (midiProps.isOpen) SongeColors.neonMagenta else Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
