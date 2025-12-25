package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import org.balch.orpheus.ui.theme.OrpheusColors

@Composable
fun CrossModSelector(isCrossQuad: Boolean = false, onToggle: (Boolean) -> Unit = {}) {
    val activeColor = if (isCrossQuad) OrpheusColors.neonCyan else Color.White.copy(alpha = 0.3f)

    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(
                    if (isCrossQuad) OrpheusColors.neonCyan.copy(alpha = 0.2f)
                    else Color(0xFF1A1A2A)
                )
                .border(1.dp, activeColor, RoundedCornerShape(4.dp))
                .clickable { onToggle(!isCrossQuad) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "34→56",
            fontSize = 7.sp,
            color =
                if (isCrossQuad) OrpheusColors.neonCyan
                else Color.White.copy(alpha = 0.4f),
            fontWeight = if (isCrossQuad) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            "78→12",
            fontSize = 7.sp,
            color =
                if (isCrossQuad) OrpheusColors.neonCyan
                else Color.White.copy(alpha = 0.4f),
            fontWeight = if (isCrossQuad) FontWeight.Bold else FontWeight.Normal
        )
    }
}
