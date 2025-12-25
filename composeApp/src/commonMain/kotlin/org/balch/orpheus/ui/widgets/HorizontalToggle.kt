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
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HorizontalToggle(
    modifier: Modifier = Modifier,
    leftLabel: String,
    rightLabel: String,
    isLeft: Boolean = true,
    onToggle: (Boolean) -> Unit,
    color: Color = OrpheusColors.warmGlow,
) {
    // Outer Box for robust click handling and touch target size
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable {
                println("[HorizontalToggle] Clicked! Current isLeft=$isLeft -> New: ${!isLeft}")
                onToggle(!isLeft)
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
                leftLabel,
                fontSize = 7.sp,
                color = if (isLeft) color else color.copy(alpha = 0.5f),
                fontWeight = if (isLeft) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )

            // Horizontal switch track (visual only)
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
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
                rightLabel,
                fontSize = 7.sp,
                color = if (!isLeft) color else color.copy(alpha = 0.5f),
                fontWeight = if (!isLeft) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )
        }
    }
}

@Preview
@Composable
fun HorizontalTogglePreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalToggle(
            leftLabel = "F",
            rightLabel = "S",
            isLeft = true,
            onToggle = {},
            color = OrpheusColors.neonCyan
        )
        HorizontalToggle(
            leftLabel = "LFO",
            rightLabel = "OFF",
            isLeft = false,
            onToggle = {},
            color = OrpheusColors.warmGlow
        )
    }
}
