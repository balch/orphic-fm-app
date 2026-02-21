package org.balch.orpheus.ui.panels.compact

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.panels.LocalCompactMode
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/**
 * Horizontal swipe-based panel switcher with arrow navigation.
 * Panels are provided dynamically via the [FeaturePanel] registration system.
 */
@Composable
fun CompactPanelSwitcher(
    panels: List<FeaturePanel>,
    selectedPanelId: PanelId,
    onPanelSelected: (PanelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = panels.indexOfFirst { it.panelId == selectedPanelId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = currentIndex) {
        panels.size
    }

    LaunchedEffect(selectedPanelId) {
        val targetPage = panels.indexOfFirst { it.panelId == selectedPanelId }.coerceAtLeast(0)
        if (targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val currentPanel = panels[pagerState.currentPage]
        if (currentPanel.panelId != selectedPanelId) {
            onPanelSelected(currentPanel.panelId)
        }
    }

    val currentColor = panels.getOrNull(currentIndex)?.color ?: Color.White

    Box(modifier = modifier.fillMaxSize()) {

        // Panel content with swipe gestures
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val panel = panels[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        OrpheusColors.darkVoid.copy(alpha = 0.6f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                CompositionLocalProvider(LocalCompactMode provides true) {
                    panel.Content(
                        modifier = Modifier.fillMaxSize(),
                        isExpanded = true,
                        onExpandedChange = {},
                        onDialogActiveChange = {},
                    )
                }
            }
        }

        // Navigation header with arrows
        PanelNavigationHeader(
            panelColor = currentColor,
            canGoLeft = currentIndex > 0,
            canGoRight = currentIndex < panels.size - 1,
            onLeftClick = {
                if (currentIndex > 0) {
                    onPanelSelected(panels[currentIndex - 1].panelId)
                }
            },
            onRightClick = {
                if (currentIndex < panels.size - 1) {
                    onPanelSelected(panels[currentIndex + 1].panelId)
                }
            }
        )

    }
}

/**
 * Navigation header with left/right arrows.
 */
@Composable
private fun PanelNavigationHeader(
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
        val previewPanels = listOf(
            previewPanel(PanelId.DELAY, "Delay", OrpheusColors.warmGlow),
            previewPanel(PanelId.DISTORTION, "Distortion", OrpheusColors.neonMagenta),
        )
        var selectedId by remember { mutableStateOf(PanelId.DELAY) }
        CompactPanelSwitcher(
            panels = previewPanels,
            selectedPanelId = selectedId,
            onPanelSelected = { selectedId = it },
            modifier = Modifier.background(OrpheusColors.darkVoid)
        )
    }
}

private fun previewPanel(id: PanelId, label: String, color: Color) =
    featurePanelPreview(
        panelId = id,
        label = label,
        color = color,
    ) { modifier, _, _, _ ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$label Panel Content",
                color = color,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }

@Preview(widthDp = 360, heightDp = 60)
@Composable
private fun PanelNavigationHeaderPreview() {
    OrpheusTheme {
        PanelNavigationHeader(
            panelColor = OrpheusColors.warmGlow,
            canGoLeft = true,
            canGoRight = true,
            onLeftClick = {},
            onRightClick = {}
        )
    }
}
