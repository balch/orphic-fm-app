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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
    onMidiClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
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
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
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
                        .clickable { isExpanded = false }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "◀",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "SETTINGS",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                
                // MIDI Setting Row
                SettingRow(
                    label = "MIDI",
                    status = midiDeviceName?.take(10) ?: "No Device",
                    isActive = isMidiOpen,
                    onClick = onMidiClick
                )
                
                // Future: Audio, Display, etc.
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
private fun SettingRow(
    label: String,
    status: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, if (isActive) SongeColors.synthGreen.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (isActive) SongeColors.synthGreen else Color.Gray,
                    CircleShape
                )
        )
        
        Column {
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                status,
                fontSize = 7.sp,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
    }
}
