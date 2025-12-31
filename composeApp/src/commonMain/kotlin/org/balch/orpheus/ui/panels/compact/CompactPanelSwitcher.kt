package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Enum representing available compact panels for portrait mode.
 */
enum class CompactPanelType(val displayName: String, val color: Color) {
    REPL("REPL", OrpheusColors.neonCyan),
    VIZ("Viz", Color(0xFF90EE90)),
    DISTORTION("Distortion", OrpheusColors.neonMagenta),
    LFO("LFO", OrpheusColors.neonCyan),
    DELAY("Delay", OrpheusColors.warmGlow),
    STEREO("Stereo", Color(0xFF008B8B)),
}

/**
 * Horizontal swipe-based panel switcher with title and arrow navigation.
 * 
 * Features:
 * - Horizontal swipe gestures to switch panels
 * - Left/right arrow buttons for navigation
 * - Panel title displayed in center
 * - Smooth animated transitions
 */
@Composable
fun CompactPanelSwitcher(
    selectedPanel: CompactPanelType,
    onPanelSelected: (CompactPanelType) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (CompactPanelType) -> Unit
) {
    val panels = CompactPanelType.entries
    val currentIndex = panels.indexOf(selectedPanel)
    var swipeDirection by remember { mutableStateOf(1) } // 1 = right, -1 = left

    Box(modifier = modifier.fillMaxSize()) {

        // Panel content with swipe gestures and animated transitions
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentIndex) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 100f
                            if (totalDrag < -threshold && currentIndex < panels.size - 1) {
                                swipeDirection = 1
                                onPanelSelected(panels[currentIndex + 1])
                            } else if (totalDrag > threshold && currentIndex > 0) {
                                swipeDirection = -1
                                onPanelSelected(panels[currentIndex - 1])
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selectedPanel,
                transitionSpec = {
                    val direction = swipeDirection
                    (slideInHorizontally { width -> direction * width } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally { width -> -direction * width } + fadeOut(tween(200)))
                },
                modifier = Modifier.fillMaxSize()
            ) { panel ->
                content(panel)
            }
        }

        // Navigation header with arrows and title
        PanelNavigationHeader(
            panelName = selectedPanel.displayName,
            panelColor = selectedPanel.color,
            canGoLeft = currentIndex > 0,
            canGoRight = currentIndex < panels.size - 1,
            onLeftClick = {
                if (currentIndex > 0) {
                    swipeDirection = -1
                    onPanelSelected(panels[currentIndex - 1])
                }
            },
            onRightClick = {
                if (currentIndex < panels.size - 1) {
                    swipeDirection = 1
                    onPanelSelected(panels[currentIndex + 1])
                }
            }
        )

    }
}

/**
 * Navigation header with panel title and left/right arrows.
 */
@Composable
private fun PanelNavigationHeader(
    panelName: String,
    panelColor: Color,
    canGoLeft: Boolean,
    canGoRight: Boolean,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .background(OrpheusColors.darkVoid.copy(alpha = 0.1f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left arrow
        NavigationArrow(
            enabled = canGoLeft,
            onClick = onLeftClick,
            isLeft = true,
            color = panelColor
        )

        // Right arrow
        NavigationArrow(
            enabled = canGoRight,
            onClick = onRightClick,
            isLeft = false,
            color = panelColor
        )
    }
}

@Composable
private fun NavigationArrow(
    enabled: Boolean,
    onClick: () -> Unit,
    isLeft: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (enabled) color else Color.Gray.copy(alpha = 0.3f),
        animationSpec = tween(200)
    )

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                        .background(color.copy(alpha = 0.1f))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isLeft) {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = if (isLeft) "Previous panel" else "Next panel",
            tint = animatedColor,
            modifier = Modifier.size(28.dp)
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 300)
@Composable
private fun CompactPanelSwitcherPreview() {
    OrpheusTheme {
        var selectedPanel by remember { mutableStateOf(CompactPanelType.DELAY) }
        CompactPanelSwitcher(
            selectedPanel = selectedPanel,
            onPanelSelected = { selectedPanel = it },
            modifier = Modifier.background(OrpheusColors.darkVoid)
        ) { panel ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding( top = 0.dp)
                    .background(panel.color.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${panel.displayName} Panel Content",
                    color = panel.color,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun PanelNavigationHeaderPreview() {
    OrpheusTheme {
        PanelNavigationHeader(
            panelName = "Delay",
            panelColor = OrpheusColors.warmGlow,
            canGoLeft = true,
            canGoRight = true,
            onLeftClick = {},
            onRightClick = {}
        )
    }
}
