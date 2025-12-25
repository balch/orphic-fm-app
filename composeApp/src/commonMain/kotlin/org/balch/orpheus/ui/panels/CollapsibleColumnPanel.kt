package org.balch.orpheus.ui.panels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects

/**
 * CompositionLocal for sharing LiquidState across panels.
 */
val LocalLiquidState = compositionLocalOf<LiquidState?> { null }

/**
 * CompositionLocal for sharing visualization-specific liquid effects across panels.
 */
val LocalLiquidEffects = compositionLocalOf { VisualizationLiquidEffects.Default }

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
    expandedWidth: Dp = 140.dp,
    expandedTitle: String? = null,
    content: @Composable () -> Unit
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

    // Base modifier - ensure minimum width for the header strip
    val baseModifier = modifier
        .widthIn(min = collapsedWidth)
        .fillMaxHeight()
        .clip(shape)

    // Apply liquid effect
    val effects = LocalLiquidEffects.current
    val liquidModifier = baseModifier.liquidVizEffects(
        liquidState = liquidState,
        scope = effects.top,
        frostAmount = effects.frostSmall.dp,
        color = color,
        tintAlpha = effects.tintAlpha,
        shape = shape
    )

    Box(
        modifier = liquidModifier
            .border(
                width = 1.dp,
                color = if (effectiveExpanded) color.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                shape = shape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .let { if (effectiveExpanded) it.fillMaxWidth() else it }
        ) {
            // [LEFT] Vertical Header Strip (Always visible)
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
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (effectiveExpanded) color else color.copy(alpha = 0.7f),
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // [RIGHT] Content Area - Fills remaining space
            // Only visible/taking space when expanded
            if (effectiveExpanded) {
                Box(
                    modifier = Modifier
                        .weight(1f) // Take all remaining space provided by parent
                        .fillMaxHeight()
                        .clipToBounds(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (expandedTitle != null) {
                            Text(
                                text = expandedTitle,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = color,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

