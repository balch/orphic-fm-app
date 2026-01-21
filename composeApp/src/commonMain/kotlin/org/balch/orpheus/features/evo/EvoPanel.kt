package org.balch.orpheus.features.evo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.HorizontalToggle
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun EvoPanel(
    modifier: Modifier = Modifier,
    evoFeature: EvoFeature = EvoViewModel.feature(),
    isExpanded: Boolean,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by evoFeature.stateFlow.collectAsState()
    // Use the selected strategy's color for accents
    val accentColor = if (uiState.isEnabled) uiState.selectedStrategy.color else Color.Gray

    CollapsibleColumnPanel(
        title = "EVO",
        color = OrpheusColors.evoGold,
        expandedTitle = "Expanse",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        // Dropdown takes available weight
        StrategyDropdown(
            selectedStrategy = uiState.selectedStrategy,
            strategies = uiState.strategies,
            onStrategySelected = evoFeature.actions.onStrategyChange,
            color = accentColor,
        )

        // 2. Controls Row - Dynamic labels from selected strategy
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Knob 1 - Label from strategy
            RotaryKnob(
                value = uiState.knob1Value,
                onValueChange = evoFeature.actions.onKnob1Change,
                label = if (uiState.isEnabled) uiState.selectedStrategy.knob1Label else "-",
                controlId = ControlIds.EVO_DEPTH,
                size = 64.dp,
                progressColor = accentColor,
                trackColor = accentColor.copy(alpha = 0.3f),
                enabled = uiState.isEnabled
            )

            // Knob 2 - Label from strategy
            RotaryKnob(
                value = uiState.knob2Value,
                onValueChange = evoFeature.actions.onKnob2Change,
                label = if (uiState.isEnabled) uiState.selectedStrategy.knob2Label else "-",
                controlId = ControlIds.EVO_RATE,
                size = 64.dp,
                progressColor = accentColor,
                trackColor = accentColor.copy(alpha = 0.3f),
                enabled = uiState.isEnabled
            )
        }

        // Enable Toggle (Mini Vertical Switch)
        HorizontalToggle(
            endLabel = "ON",
            startLabel = "OFF",
            isStart = !uiState.isEnabled,
            onToggle = {
                evoFeature.actions.onEnabledChange(!it)
            },
            color = accentColor,
        )
    }
}

@Composable
private fun StrategyDropdown(
    selectedStrategy: AudioEvolutionStrategy,
    strategies: List<AudioEvolutionStrategy>,
    onStrategySelected: (AudioEvolutionStrategy) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clickable { expanded = true }
            .clip(RoundedCornerShape(8.dp))
            .background(OrpheusColors.darkVoid.copy(alpha = 0.5f))
            .padding(6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.wrapContentWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedStrategy.name,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select Strategy",
                tint = color
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(OrpheusColors.panelSurface) // Consistent background
        ) {
            strategies.forEach { strategy ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = strategy.name,
                            color = if (strategy.id == selectedStrategy.id) strategy.color else Color.White
                        )
                    },
                    onClick = {
                        onStrategySelected(strategy)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Preview support
@Preview(widthDp = 400, heightDp = 400)
@Composable
fun EvoPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        EvoPanel(
            isExpanded = true,
            evoFeature =  EvoViewModel.previewFeature(),
        )
    }
}
