package org.balch.songe.ui.panels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.theme.SongeColors

/**
 * Collapsible settings panel for the left side of top row.
 * Shows a persistent vertical header strip on the left.
 *
 * When collapsed: only shows 28dp header strip
 * When expanded: shows header strip + expandedWidth content area
 */
@Composable
fun CollapsibleColumnPanel(
    title: String,
    color: Color,
    initialExpanded: Boolean = false,
    expandedWidth: Dp = 140.dp,
    modifier: Modifier = Modifier,
    // Kept for API compatibility but not used
    minExpandedWidth: Dp = 120.dp,
    maxExpandedWidth: Dp = 400.dp,
    useFlexWidth: Boolean = false,
    content: @Composable () -> Unit
) {
    var isExpanded by remember { mutableStateOf(initialExpanded) }

    val collapsedWidth = 28.dp

    // Animate the content width
    val contentWidth by animateDpAsState(
        targetValue = if (isExpanded) expandedWidth else 0.dp,
        animationSpec = tween(durationMillis = 300)
    )

    Box(
        modifier = modifier
            .width(collapsedWidth + contentWidth)
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
            .border(
                width = 1.dp,
                color = if (isExpanded) color.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // [LEFT] Vertical Header Strip (Always visible)
            Box(
                modifier = Modifier
                    .width(collapsedWidth)
                    .fillMaxHeight()
                    .clickable { isExpanded = !isExpanded }
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                // Vertical Text - Letters stacked vertically for better readability
                Text(
                    text = title.toList().joinToString("\n"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isExpanded) color else color.copy(alpha = 0.7f),
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // [RIGHT] Content Area (Visible only when expanded, animated width)
            if (contentWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .padding(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
