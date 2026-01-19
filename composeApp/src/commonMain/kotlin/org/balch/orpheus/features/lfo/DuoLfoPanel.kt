package org.balch.orpheus.features.lfo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.Vertical3WaySwitch
import org.balch.orpheus.ui.widgets.VerticalToggle
import org.balch.orpheus.ui.widgets.learnable
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

enum class HyperLfoMode {
    AND,
    OFF,
    OR
}

/**
 * HyperLfoPanel consuming feature() interface.
 */
@Composable
fun DuoLfoPanel(
    feature: LfoFeature = LfoViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "LFO",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Pong",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {

        val learnState = LocalLearnModeState.current
        val isActive = uiState.mode != HyperLfoMode.OFF

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 3-way AND/OFF/OR Switch (Left)
            Box(
                modifier =
                    Modifier
                        .learnable(ControlIds.HYPER_LFO_MODE, learnState)
            ) {
                Vertical3WaySwitch(
                    topLabel = "AND",
                    bottomLabel = "OR",
                    position =
                        when (uiState.mode) {
                            HyperLfoMode.AND -> 0
                            HyperLfoMode.OFF -> 1
                            HyperLfoMode.OR -> 2
                        },
                    onPositionChange = { pos ->
                        actions.onModeChange(
                            when (pos) {
                                0 -> HyperLfoMode.AND
                                1 -> HyperLfoMode.OFF
                                else -> HyperLfoMode.OR
                            }
                        )
                    },
                    color = OrpheusColors.neonCyan,
                    enabled = !learnState.isActive
                )
            }

            // Knobs (Medium size - 56dp)
            RotaryKnob(
                value = uiState.lfoA,
                onValueChange = actions.onLfoAChange,
                label = "RATE 1",
                controlId = ControlIds.HYPER_LFO_A,
                size = 64.dp,
                progressColor =
                    if (isActive) OrpheusColors.neonCyan
                    else OrpheusColors.neonCyan.copy(alpha = 0.4f)
            )
            RotaryKnob(
                value = uiState.lfoB,
                onValueChange = actions.onLfoBChange,
                label = "RATE 2",
                controlId = ControlIds.HYPER_LFO_B,
                size = 64.dp,
                progressColor =
                    if (isActive) OrpheusColors.neonCyan
                    else OrpheusColors.neonCyan.copy(alpha = 0.4f)
            )

            // LINK Vertical Switch (Right)
            Box(modifier = Modifier.learnable(ControlIds.HYPER_LFO_LINK, learnState)) {
                VerticalToggle(
                    topLabel = "LINK",
                    bottomLabel = "OFF",
                    isTop = uiState.linkEnabled,
                    onToggle = { actions.onLinkChange(it) },
                    color = OrpheusColors.neonCyan,
                    enabled = !learnState.isActive
                )
            }
        }
    }
}

@Composable
private fun ModeToggleButton(
    modifier: Modifier = Modifier,
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    activeColor: Color
) {
    Box(
        modifier =
            modifier.clip(RoundedCornerShape(6.dp))
                .background(
                    if (isSelected) activeColor.copy(alpha = 0.8f)
                    else OrpheusColors.panelSurface
                )
                .border(
                    width = 1.dp,
                    color = if (isSelected) activeColor else OrpheusColors.lfoBackground,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) Color.White else OrpheusColors.greyText
        )
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun HyperLfoPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        DuoLfoPanel(
            feature = LfoViewModel.previewFeature()
        )
    }
}
