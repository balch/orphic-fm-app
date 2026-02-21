package org.balch.orpheus.ui.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.features.FeaturePanel

/**
 * A container for the top header panel row that manages expansion state
 * and applies weight-based layout to expanded panels.
 */
@Composable
fun HeaderPanel(
    modifier: Modifier = Modifier,
    headerFeature: HeaderFeature = HeaderViewModel.feature(),
    panels: List<FeaturePanel> = headerFeature.visiblePanels,
    height: Dp = 260.dp,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    val uiState by headerFeature.stateFlow.collectAsState()
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        panels.forEach { panel ->
            val isExpanded = uiState.isExpanded(panel.panelId)
            val weight = uiState.weightOverrides[panel.panelId] ?: panel.weight
            panel.Content(
                modifier = panelModifier(isExpanded, weight),
                isExpanded = isExpanded,
                onExpandedChange = { headerFeature.actions.setExpanded(panel.panelId, it) },
                onDialogActiveChange = onDialogActiveChange,
            )
        }
    }
}

/**
 * Returns the appropriate modifier for a panel based on its expansion state.
 * Expanded panels get weight() to share available space proportionally.
 * Collapsed panels use their intrinsic width (just the header).
 */
@Composable
private fun RowScope.panelModifier(
    isExpanded: Boolean,
    weight: Float = 1f,
    minWidth: Dp = 320.dp
): Modifier {
    return if (isExpanded) {
        Modifier
            .fillMaxHeight()
            .widthIn(min = minWidth) // Minimum width to prevent UX compaction
            .weight(weight)
    } else {
        Modifier.fillMaxHeight()
    }
}
