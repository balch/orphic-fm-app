package org.balch.orpheus.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.lighten

/**
 * Collapsible settings panel for the left side of top row.
 * Shows a persistent vertical header strip on the left.
 * Applies liquid blur effect when LiquidState is provided via LocalLiquidState.
 *
 * When collapsed: only shows 28dp header strip with vertical title
 * When expanded: shows header strip + content area with expandedTitle at top
 * 
 * @param title Short title shown vertically when collapsed (e.g., "VIZ")
 * @param expandedTitle Optional title shown at top of content when expanded (e.g., "Background")
 *                      If null, no header is shown in content area
 */
@Composable
fun CollapsibleColumnPanel(
    modifier: Modifier = Modifier,
    title: String,
    color: Color,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    initialExpanded: Boolean = false,
    expandedTitle: String? = null,
    showCollapsedHeader: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var internalExpanded by remember { mutableStateOf(initialExpanded) }
    val effectiveExpanded = isExpanded ?: internalExpanded

    val toggleExpanded = {
        val next = !effectiveExpanded
        if (isExpanded == null) {
            internalExpanded = next
        }
        onExpandedChange?.invoke(next)
    }

    val liquidState = LocalLiquidState.current
    val collapsedWidth = 28.dp

    val shape = RoundedCornerShape(8.dp)

    // Apply liquid effect
    val effects = LocalLiquidEffects.current

    Box(
        modifier = modifier.fillMaxHeight()
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.top,
                frostAmount = effects.frostSmall.dp,
                color = color,
                tintAlpha = effects.tintAlpha,
                shape = shape
            )
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (effectiveExpanded) color.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                shape = shape
            )
    ) {
        Row {
            // [LEFT] Vertical Header Strip (Visible if enabled)
            if (showCollapsedHeader) {
                Box(
                    modifier = Modifier
                        .width(collapsedWidth)
                        .fillMaxHeight()
                        .clickable { toggleExpanded() }
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.toList().joinToString("\n"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (effectiveExpanded) color else color.lighten(),
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (effectiveExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical =
                                if (showCollapsedHeader) 16.dp else 4.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (expandedTitle != null) {
                        Text(
                            text = expandedTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = color,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    content()
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
