package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.roundToInt

/**
 * A modeless, draggable and resizable dialog with liquid background effects.
 * Uses hoisted state for position and size to maintain persistence.
 */
@Composable
fun DraggableDialog(
    title: String = AppConfig.CHAT_DISPLAY_NAME,
    emoji: String? = AppConfig.CHAT_EMOJI,
    onClose: () -> Unit,
    liquidState: LiquidState? = null,
    position: Pair<Float, Float>,
    onPositionChange: (Float, Float) -> Unit,
    size: Pair<Float, Float>,
    onSizeChange: (Float, Float) -> Unit,
    minWidth: Dp = 300.dp,
    minHeight: Dp = 300.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val (offsetX, offsetY) = position
    val (dialogWidth, dialogHeight) = size
    
    // Capture latest state for gesture detectors to avoid staleness
    val currentPosition by rememberUpdatedState(position)
    val currentSize by rememberUpdatedState(size)
    val updatedOnPositionChange by rememberUpdatedState(onPositionChange)
    val updatedOnSizeChange by rememberUpdatedState(onSizeChange)
    
    val shape = RoundedCornerShape(12.dp)
    val handleSize = 24.dp // Larger hit area
    
    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(dialogWidth.dp, dialogHeight.dp)
    ) {
        // Main dialog content
        val baseModifier = Modifier
            .fillMaxSize()
            .clip(shape)
        
        // Use supplied liquidState (from App) for transparency, or fallback
        val backgroundModifier = if (liquidState != null) {
            baseModifier.liquid(liquidState) {
                frost = 16.dp
                this.shape = shape
                tint = OrpheusColors.brownsBrown.copy(alpha = 0.8f) // Deep brown glass effect
            }
        } else {
            baseModifier.background(OrpheusColors.brownsBrown.copy(alpha = 0.95f))
        }
        
        Box(
            modifier = backgroundModifier
                .border(1.dp, OrpheusColors.brownsOrange.copy(alpha = 0.5f), shape)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar - draggable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(OrpheusColors.brownsBrown.copy(alpha = 0.5f))
                        .pointerHoverIcon(PointerIcon.Hand) // Indicate draggable
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val (curX, curY) = currentPosition
                                updatedOnPositionChange(
                                    curX + dragAmount.x,
                                    curY + dragAmount.y
                                )
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (emoji != null) {
                        // Emoji icon
                        Text(
                            text = emoji,
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    // Title
                    Text(
                        text = title,
                        color = OrpheusColors.brownsOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Close button
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "✕",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                    }
                }
                
                // Content area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    content()
                }
            }
        }
        
        // Resize handle - bottom right corner
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(handleSize)
                .pointerHoverIcon(PointerIcon.Hand) // Indicate resizable
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val (curW, curH) = currentSize
                        val newWidth = maxOf(minWidth.value, curW + dragAmount.x) // Simple mapping assuming density ~1 or robust enough
                        val newHeight = maxOf(minHeight.value, curH + dragAmount.y)
                        updatedOnSizeChange(newWidth, newHeight)
                    }
                }
        ) {
            // Visual resize indicator
            Text(
                text = "⋰",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }
    }
}
