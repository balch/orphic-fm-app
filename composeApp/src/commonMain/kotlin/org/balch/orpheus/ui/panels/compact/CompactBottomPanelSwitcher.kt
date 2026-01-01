package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Bottom panel switcher with oval nav bar for compact portrait mode.
 *
 * Features:
 * - Horizontal swipe gestures to switch between AI, Pads, and Strings panels
 * - Oval bottom nav bar with labeled pill buttons
 * - Selected pill highlighted with panel color and glow
 * - Smooth animated transitions
 */
@Composable
fun CompactBottomPanelSwitcher(
    selectedPanel: CompactBottomPanelType,
    onPanelSelected: (CompactBottomPanelType) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (CompactBottomPanelType) -> Unit
) {
    val panels = CompactBottomPanelType.entries
    val currentIndex = panels.indexOf(selectedPanel)
    var swipeDirection by remember { mutableStateOf(1) } // 1 = right, -1 = left

    Column(modifier = modifier.fillMaxSize()) {
        // Panel content with swipe gestures
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
            // Use Crossfade instead of AnimatedContent to preserve liquid effects
            Crossfade(
                targetState = selectedPanel,
                animationSpec = tween(200),
                modifier = Modifier.fillMaxSize()
            ) { panel ->
                content(panel)
            }
        }

        // Oval bottom nav bar
        OvalBottomNavBar(
            selectedPanel = selectedPanel,
            onPanelSelected = { panel ->
                val newIndex = panels.indexOf(panel)
                swipeDirection = if (newIndex > currentIndex) 1 else -1
                onPanelSelected(panel)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
}

/**
 * Oval bottom navigation bar with pill buttons and liquid glass effect.
 */
@Composable
private fun OvalBottomNavBar(
    selectedPanel: CompactBottomPanelType,
    onPanelSelected: (CompactBottomPanelType) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current

    val baseModifier = modifier
        .height(48.dp)
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.15f), shape)
        .padding(horizontal = 4.dp, vertical = 4.dp)

    val navModifier = if (liquidState != null) {
        baseModifier.liquidVizEffects(
            liquidState = liquidState,
            scope = effects.bottom,
            frostAmount = effects.frostMedium.dp,
            color = OrpheusColors.softPurple,
            tintAlpha = effects.tintAlpha,
            shape = shape,
        )
    } else {
        baseModifier.background(OrpheusColors.darkVoid.copy(alpha = 0.8f))
    }

    Row(
        modifier = navModifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactBottomPanelType.entries.forEach { panel ->
            NavPill(
                panel = panel,
                isSelected = panel == selectedPanel,
                onClick = { onPanelSelected(panel) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Individual nav pill button.
 */
@Composable
private fun NavPill(
    panel: CompactBottomPanelType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) panel.color.copy(alpha = 0.25f) else Color.Transparent,
        animationSpec = tween(200)
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) panel.color.copy(alpha = 0.6f) else Color.Transparent,
        animationSpec = tween(200)
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) panel.color else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(200)
    )

    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(40.dp)
            .clip(shape)
            .then(
                if (isSelected) {
                    Modifier.shadow(8.dp, shape, ambientColor = panel.color, spotColor = panel.color)
                } else {
                    Modifier
                }
            )
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = panel.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 400)
@Composable
private fun CompactBottomPanelSwitcherPreview() {
    OrpheusTheme {
        var selectedPanel by remember { mutableStateOf(CompactBottomPanelType.AI) }
        CompactBottomPanelSwitcher(
            selectedPanel = selectedPanel,
            onPanelSelected = { selectedPanel = it },
            modifier = Modifier.background(OrpheusColors.darkVoid)
        ) { panel ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
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
private fun OvalBottomNavBarPreview() {
    OrpheusTheme {
        var selectedPanel by remember { mutableStateOf(CompactBottomPanelType.PADS) }
        Box(modifier = Modifier.background(OrpheusColors.darkVoid)) {
            OvalBottomNavBar(
                selectedPanel = selectedPanel,
                onPanelSelected = { selectedPanel = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}
