package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

enum class Switch3WayState {
    START, MIDDLE, END
}

@Composable
fun HorizontalSwitch3Way(
    modifier: Modifier = Modifier,
    state: Switch3WayState,
    startText: String,
    endText: String,
    onStateChange: (Switch3WayState) -> Unit,
    color: Color = OrpheusColors.warmGlow,
    controlId: String? = null,
) {
    val maxChars = listOf(startText, endText).maxOf { it.length }
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
            .pointerInput(isActive) {
                if (isActive) return@pointerInput
                detectTapGestures { offset ->
                    val width = size.width
                    // Tighter Center Zone (Visual Thumb is approx 15% of total width)
                    // 0..0.42 -> Left (LFO)
                    // 0.42..0.58 -> Center (OFF)
                    // 0.58..1.0 -> Right (FM)
                    val fraction = offset.x / width.toFloat()

                    val newState = when {
                        fraction < 0.42f -> Switch3WayState.START
                        fraction < 0.58f -> Switch3WayState.MIDDLE
                        else -> Switch3WayState.END
                    }
                    onStateChange(newState)
                }
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Left Label (LFO)
            Text(
                startText.padStart(maxChars),
                fontSize = 7.sp,
                color = if (state == Switch3WayState.START) color else color.copy(alpha = 0.5f),
                fontWeight = if (state == Switch3WayState.START) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )

            // Switch Track (Horizontal)
            Box(
                modifier = Modifier
                    .width(40.dp) // Match HorizontalToggle width
                    .height(12.dp) // Match HorizontalToggle height
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f))
            ) {
                // Calculate Thumb Offset (X axis)
                // 3 positions: Left (LFO), Center (OFF), Right (FM)
                // Track Width = 40.dp
                // Thumb Width = 13.dp (approx 1/3)

                val thumbWidth = 13.dp
                val trackWidth = 40.dp

                // Offsets (from left)
                // LFO (Left) = 0.dp
                // OFF (Center) = (40 - 13) / 2 = 13.5dp
                // FM (Right) = 40 - 13 = 27.dp

                val targetOffset = when (state) {
                    Switch3WayState.START -> 0.dp
                    Switch3WayState.MIDDLE -> (trackWidth - thumbWidth) / 2
                    Switch3WayState.END -> trackWidth - thumbWidth
                }

                val animatedOffset by animateDpAsState(targetOffset)

                // Thumb
                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .padding(2.dp)
                        .fillMaxHeight()
                        .width(thumbWidth)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }

            Text(
                endText.padEnd(maxChars),
                fontSize = 7.sp,
                color = if (state == Switch3WayState.END) color else color.copy(alpha = 0.5f),
                fontWeight = if (state == Switch3WayState.END) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 9.sp
            )
        }
    }
}

@Preview
@Composable
fun HorizontalSwitch3WayPreview() {
    OrpheusTheme {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalSwitch3Way(
                state = Switch3WayState.MIDDLE,
                onStateChange = {},
                color = OrpheusColors.neonMagenta,
                startText = "LFO",
                endText = "FM",
            )
            HorizontalSwitch3Way(
                state = Switch3WayState.END,
                onStateChange = {},
                color = OrpheusColors.neonMagenta,
                startText = "LFO",
                endText = "FM",
            )
            HorizontalSwitch3Way(
                state = Switch3WayState.START,
                onStateChange = {},
                color = OrpheusColors.neonMagenta,
                startText = "LFO",
                endText = "FM",
            )
        }
    }
}
