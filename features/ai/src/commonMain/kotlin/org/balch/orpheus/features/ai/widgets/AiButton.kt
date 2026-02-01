package org.balch.orpheus.features.ai.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.github.fletchmckee.liquid.liquid
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * AI feature button with liquid background effect.
 * 
 * Displays a text label with unique color, supports toggle state.
 * 
 * @param label Text label for the button
 * @param color Unique color for this button
 * @param isActive Whether the feature is currently active (toggle state)
 * @param onClick Callback when button is clicked
 * @param modifier Modifier for the button
 */
@Composable
fun AiButton(
    label: String,
    color: Color,
    isActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val liquidState = LocalLiquidState.current
    
    val shape = RoundedCornerShape(8.dp)
    val currentColor = if (isActive) color else color.copy(alpha = 0.6f)
    
    val baseModifier = modifier
        .size(width = 72.dp, height = 40.dp)
        .clip(shape)
    
    val backgroundModifier = if (liquidState != null) {
        baseModifier.liquid(liquidState) {
            frost = 8.dp
            this.shape = shape
            tint = OrpheusColors.darkVoid.copy(alpha = 0.5f)
        }
    } else {
        baseModifier.background(OrpheusColors.darkVoid.copy(alpha = 0.7f))
    }
    
    Box(
        modifier = backgroundModifier
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) color.copy(alpha = 0.8f) else color.copy(alpha = 0.3f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = currentColor,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
    }
}

