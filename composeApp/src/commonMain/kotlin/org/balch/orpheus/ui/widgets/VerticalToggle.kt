package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
fun VerticalToggle(
    modifier: Modifier = Modifier,
    topLabel: String,
    bottomLabel: String,
    isTop: Boolean = true,
    onToggle: (Boolean) -> Unit,
    color: Color = OrpheusColors.warmGlow,
    enabled: Boolean = true,
    controlId: String? = null
) {
    val learnState = LocalLearnModeState.current
    val isActive = learnState.isActive

    val finalModifier = if (controlId != null) {
        modifier.learnable(controlId, learnState)
    } else {
        modifier
    }

    // Outer Box for robust click handling
    Box(
        modifier = finalModifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.panelBackground)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .let {
                if (enabled && !isActive) {
                    it.clickable {
                        println("[VerticalToggle] Clicked! Current isTop=$isTop -> New: ${!isTop}")
                        onToggle(!isTop)
                    }
                } else {
                    it
                }
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Top label
            Text(
                topLabel,
                fontSize = 7.sp,
                color = if (isTop) color else color.copy(alpha = 0.5f),
                fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )

            // Vertical switch track
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = if (isTop) Alignment.TopCenter else Alignment.BottomCenter
            ) {
                // Switch knob
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }

            // Bottom label
            Text(
                bottomLabel,
                fontSize = 7.sp,
                color = if (!isTop) color else color.copy(alpha = 0.5f),
                fontWeight = if (!isTop) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )
        }
    }
}

@Preview
@Composable
fun VerticalTogglePreview() {
    OrpheusTheme {
        Column {
            VerticalToggle(
                topLabel = "LFO",
                bottomLabel = "OFF",
                isTop = true,
                onToggle = {},
                color = OrpheusColors.warmGlow
            )
            VerticalToggle(
                topLabel = "LFO",
                bottomLabel = "OFF",
                isTop = false,
                onToggle = {},
                color = OrpheusColors.neonCyan
            )
        }
    }
}
