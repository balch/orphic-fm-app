package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HorizontalToggle(
    modifier: Modifier = Modifier,
    startLabel: String,
    endLabel: String,
    isStart: Boolean = true,
    onToggle: (Boolean) -> Unit,
    color: Color = OrpheusColors.warmGlow,
) {
    val maxChars = listOf(startLabel, endLabel).maxOf { it.length }

    // Outer Box for robust click handling and touch target size
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable {
                println("[HorizontalToggle] Clicked! Current isLeft=$isStart -> New: ${!isStart}")
                onToggle(!isStart)
            }
            .padding(horizontal = 6.dp, vertical = 6.dp), // Padding inside container
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Left label
            Text(
                startLabel.padStart(maxChars),
                fontSize = 7.sp,
                color = if (isStart) color else color.copy(alpha = 0.5f),
                fontWeight = if (isStart) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )

            // Horizontal switch track (visual only)
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = if (isStart) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                // Switch thumb
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }

            // Right label
            Text(
                endLabel.padEnd(maxChars),
                fontSize = 7.sp,
                color = if (!isStart) color else color.copy(alpha = 0.5f),
                fontWeight = if (!isStart) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )
        }
    }
}

@Preview
@Composable
fun HorizontalTogglePreview() {
    OrpheusTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalToggle(
                startLabel = "F",
                endLabel = "S",
                isStart = true,
                onToggle = {},
                color = OrpheusColors.neonCyan
            )
            HorizontalToggle(
                startLabel = "LFO",
                endLabel = "OFF",
                isStart = false,
                onToggle = {},
                color = OrpheusColors.warmGlow
            )
        }
    }
}
