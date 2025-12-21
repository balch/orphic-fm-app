package org.balch.songe.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.balch.songe.input.MidiMappingState
import org.balch.songe.ui.theme.SongeColors

/**
 * Dialog for configuring MIDI note-to-voice mappings.
 * Features "Learn" mode where the next MIDI note received is assigned to a voice.
 */
@Composable
fun MidiMappingDialog(
    mappingState: MidiMappingState,
    onMappingChange: (MidiMappingState) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A2A))
                .border(1.dp, SongeColors.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                "MIDI MAPPING",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SongeColors.neonCyan
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                "Tap LEARN, then press a key on your MIDI controller",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Voice mappings grid
            (0 until 8).forEach { voiceIndex ->
                VoiceMappingRow(
                    voiceIndex = voiceIndex,
                    mappingState = mappingState,
                    onLearnClick = { 
                        onMappingChange(mappingState.startLearn(voiceIndex))
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onMappingChange(mappingState.reset()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SongeColors.warmGlow
                    )
                ) {
                    Text("Reset", fontSize = 12.sp)
                }
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SongeColors.neonCyan.copy(alpha = 0.3f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Done", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun VoiceMappingRow(
    voiceIndex: Int,
    mappingState: MidiMappingState,
    onLearnClick: () -> Unit
) {
    val isLearning = mappingState.learnMode == voiceIndex
    val assignedNote = mappingState.voiceMappings.entries
        .find { it.value == voiceIndex }?.key
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isLearning) SongeColors.neonMagenta.copy(alpha = 0.2f)
                else Color(0xFF252535)
            )
            .border(
                width = 1.dp,
                color = if (isLearning) SongeColors.neonMagenta else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Voice label
        Text(
            "Voice ${voiceIndex + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Current assignment
        Text(
            assignedNote?.let { MidiMappingState.noteName(it) } ?: "—",
            fontSize = 12.sp,
            color = if (assignedNote != null) SongeColors.synthGreen else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.width(40.dp)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Learn button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isLearning) SongeColors.neonMagenta
                    else SongeColors.neonCyan.copy(alpha = 0.3f)
                )
                .clickable { onLearnClick() }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isLearning) "WAITING..." else "LEARN",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
