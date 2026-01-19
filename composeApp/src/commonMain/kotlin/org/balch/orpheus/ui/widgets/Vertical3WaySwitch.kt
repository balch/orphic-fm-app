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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * 3-way vertical switch for AND/OFF/OR selection. position: 0 = top (AND), 1 = middle (OFF), 2 =
 * bottom (OR)
 */
@Composable
fun Vertical3WaySwitch(
    topLabel: String,
    bottomLabel: String,
    position: Int, // 0=top, 1=middle, 2=bottom
    onPositionChange: (Int) -> Unit,
    color: Color,
    enabled: Boolean = true
) {
    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.panelBackground)
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(
                    horizontal = 6.dp,
                    vertical = 6.dp
                ), // Increased vertical padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp) // Little more space
    ) {
        // Top label (AND)
        Text(
            topLabel,
            fontSize = 7.sp,
            color = if (position == 0) color else color.copy(alpha = 0.5f),
            fontWeight = if (position == 0) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier.clickable(enabled = enabled) { onPositionChange(0) }
        )

        // 3-way switch track
        Box(
            modifier =
                Modifier.width(12.dp)
                    .height(40.dp) // Standardized height
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press ||
                                    event.type == PointerEventType.Move
                                ) {
                                    val change = event.changes.firstOrNull() ?: continue
                                    if (change.pressed) {
                                        val y = change.position.y
                                        val height = size.height

                                        // Calculate section (0, 1, 2)
                                        // 0 = Top, 1 = Middle, 2 = Bottom
                                        val section =
                                            (y / (height / 3))
                                                .toInt()
                                                .coerceIn(0, 2)
                                        onPositionChange(section)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
        ) {
            // Switch knob - position determines alignment
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .fillMaxHeight(0.33f)
                        .align(
                            when (position) {
                                0 -> Alignment.TopCenter
                                1 -> Alignment.Center
                                else -> Alignment.BottomCenter
                            }
                        )
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (position == 1) color.copy(alpha = 0.5f) else color
                        )
            )
        }

        // Bottom label (OR)
        Text(
            bottomLabel,
            fontSize = 7.sp,
            color = if (position == 2) color else color.copy(alpha = 0.5f),
            fontWeight = if (position == 2) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier.clickable { onPositionChange(2) }
        )
    }
}
