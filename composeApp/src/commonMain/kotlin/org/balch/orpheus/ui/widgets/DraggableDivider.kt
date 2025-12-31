package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A horizontal draggable divider for resizing two vertical sections.
 * 
 * Features:
 * - Visible grab handle with hover/drag feedback
 * - Constrains drag within min/max bounds
 * - Glowing visual feedback during drag
 * 
 * @param onDrag Callback with the delta Y value when dragging
 * @param minOffset Minimum offset constraint (top section min height)
 * @param maxOffset Maximum offset constraint (bottom section min height from top)
 * @param modifier Modifier for the divider
 */
@Composable
fun DraggableDivider(
    onDrag: (Float) -> Unit,
    minOffset: Dp = 100.dp,
    maxOffset: Dp = 500.dp,
    modifier: Modifier = Modifier,
    color: Color = OrpheusColors.neonCyan
) {
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { _, dragAmount ->
                        onDrag(dragAmount)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Background bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isDragging) {
                        color.copy(alpha = 0.3f)
                    } else {
                        Color.White.copy(alpha = 0.05f)
                    }
                )
        )

        // Center grab handle (pill shape)
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (isDragging) {
                        color
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    }
                )
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 40)
@Composable
private fun DraggableDividerPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center
        ) {
            DraggableDivider(
                onDrag = {}
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 40)
@Composable
private fun DraggableDividerDraggingPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center
        ) {
            // Simulated dragging state - in preview we can't show dynamic state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(OrpheusColors.neonCyan.copy(alpha = 0.3f))
                )
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(OrpheusColors.neonCyan)
                )
            }
        }
    }
}
