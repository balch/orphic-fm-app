package org.balch.orpheus.ui.widgets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HorizontalSlidingSwitch(
    isLeft: Boolean, // true = left option selected, false = right option selected
    onToggle: (Boolean) -> Unit,
    leftLabel: String,
    rightLabel: String,
    activeColor: Color,
    inactiveColor: Color = Color(0xFF1A1A2A),
    width: Dp = 46.dp,
    height: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    // Thumb properties
    val thumbSize = height - 4.dp
    val trackPadding = 2.dp

    // Animate thumb position
    val thumbOffset by animateDpAsState(
        targetValue = if (isLeft) trackPadding else (width - thumbSize - trackPadding),
        animationSpec = tween(durationMillis = 200),
        label = "thumbOffset"
    )

    // Main Track Container
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(inactiveColor)
            .border(1.dp, activeColor.copy(alpha = 0.3f), RoundedCornerShape(height / 2))
            .clickable { onToggle(!isLeft) },
        contentAlignment = Alignment.CenterStart
    ) {
        // Labels (positioned at ends)
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = leftLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLeft) Color.White else Color.Gray.copy(alpha = 0.5f)
            )
            Text(
                text = rightLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (!isLeft) Color.White else Color.Gray.copy(alpha = 0.5f)
            )
        }

        // Sliding Circular Thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbSize)
                .height(thumbSize)
                .clip(RoundedCornerShape(50)) // Circle
                .background(activeColor)
            // Add a subtle shadow or overlay for depth if possible, or just solid color
        )
    }
}
