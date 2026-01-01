package org.balch.orpheus.features.lfo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.learnable
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

enum class HyperLfoMode {
    AND,
    OFF,
    OR
}

/** Smart wrapper that connects LfoViewModel to the layout. */
@Composable
fun HyperLfoPanel(
    modifier: Modifier = Modifier,
    viewModel: LfoViewModel = metroViewModel(),
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val actions = viewModel.panelActions

    HyperLfoPanelLayout(
        uiState = state,
        actions = actions,
        modifier = modifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange
    )
}

/**
 * Hyper LFO Panel - Two LFOs combined with AND/OR logic
 *
 * In "AND" mode: Both LFOs must be high for output In "OR" mode: Either LFO high produces output
 */
@Composable
fun HyperLfoPanelLayout(
    modifier: Modifier = Modifier,
    uiState: LfoUiState,
    actions: LfoPanelActions,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    CollapsibleColumnPanel(
        title = "LFO",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Hyper LFO",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        val learnState = LocalLearnModeState.current
        val isActive = uiState.mode != HyperLfoMode.OFF

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Spacer(modifier = Modifier.height(4.dp))

            // Controls Row - knobs and switches aligned
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 3-way AND/OFF/OR Switch (Left)
                Box(modifier = Modifier.learnable(ControlIds.HYPER_LFO_MODE, learnState)) {
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
                    label = "FREQ A",
                    controlId = ControlIds.HYPER_LFO_A,
                    size = 56.dp,
                    progressColor =
                        if (isActive) OrpheusColors.neonCyan
                        else OrpheusColors.neonCyan.copy(alpha = 0.4f)
                )
                RotaryKnob(
                    value = uiState.lfoB,
                    onValueChange = actions.onLfoBChange,
                    label = "FREQ B",
                    controlId = ControlIds.HYPER_LFO_B,
                    size = 56.dp,
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

            Spacer(modifier = Modifier.weight(1f))
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
                    else Color(0xFF2A2A3A)
                )
                .border(
                    width = 1.dp,
                    color = if (isSelected) activeColor else Color(0xFF4A4A5A),
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
            color = if (isSelected) Color.White else Color(0xFF888888)
        )
    }
}

@Composable
private fun VerticalToggle(
    topLabel: String,
    bottomLabel: String,
    isTop: Boolean = true,
    onToggle: (Boolean) -> Unit,
    color: Color,
    enabled: Boolean = true
) {
    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1A1A2A))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(
                    horizontal = 6.dp,
                    vertical = 6.dp
                ), // Increased vertical padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp) // Little more space
    ) {
        // Top label
        Text(
            topLabel,
            fontSize = 7.sp,
            color = if (isTop) color else color.copy(alpha = 0.5f),
            fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp // Explicit line height
        )

        // Vertical switch track
        Box(
            modifier =
                Modifier.width(12.dp)
                    .height(40.dp) // Standardized height
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f))
                    .let { if (enabled) it.clickable { onToggle(!isTop) } else it },
            contentAlignment = if (isTop) Alignment.TopCenter else Alignment.BottomCenter
        ) {
            // Switch knob
            Box(
                modifier =
                    Modifier.padding(2.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
            )
        }

        // Bottom label
        Text(
            bottomLabel,
            fontSize = 7.sp,
            color = if (!isTop) color else color.copy(alpha = 0.5f),
            fontWeight = if (!isTop) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp
        )
    }
}

/**
 * 3-way vertical switch for AND/OFF/OR selection. position: 0 = top (AND), 1 = middle (OFF), 2 =
 * bottom (OR)
 */
@Composable
private fun Vertical3WaySwitch(
    topLabel: String,
    bottomLabel: String,
    position: Int, // 0=top, 1=middle, 2=bottom
    onPositionChange: (Int) -> Unit,
    color: Color,
    enabled: Boolean = true
) {
    Column(
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1A1A2A))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(
                    horizontal = 6.dp,
                    vertical = 6.dp
                ), // Increased vertical padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp) // Little more space
    ) {
        // Top label (AND)
        Text(
            topLabel,
            fontSize = 7.sp,
            color = if (position == 0) color else color.copy(alpha = 0.5f),
            fontWeight = if (position == 0) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier.clickable(enabled = enabled) { onPositionChange(0) }
        )

        // 3-way switch track
        Box(
            modifier =
                Modifier.width(12.dp)
                    .height(40.dp) // Standardized height
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press ||
                                    event.type == PointerEventType.Move
                                ) {
                                    val change = event.changes.firstOrNull() ?: continue
                                    if (change.pressed) {
                                        val y = change.position.y
                                        val height = size.height

                                        // Calculate section (0, 1, 2)
                                        // 0 = Top, 1 = Middle, 2 = Bottom
                                        val section =
                                            (y / (height / 3))
                                                .toInt()
                                                .coerceIn(0, 2)
                                        onPositionChange(section)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
        ) {
            // Switch knob - position determines alignment
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .fillMaxHeight(0.33f)
                        .align(
                            when (position) {
                                0 -> Alignment.TopCenter
                                1 -> Alignment.Center
                                else -> Alignment.BottomCenter
                            }
                        )
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (position == 1) color.copy(alpha = 0.5f) else color
                        )
            )
        }

        // Bottom label (OR)
        Text(
            bottomLabel,
            fontSize = 7.sp,
            color = if (position == 2) color else color.copy(alpha = 0.5f),
            fontWeight = if (position == 2) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 9.sp,
            modifier = Modifier.clickable { onPositionChange(2) }
        )
    }
}

@Preview(widthDp = 320, heightDp = 240)
@Composable
fun HyperLfoPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        HyperLfoPanelLayout(
            uiState = LfoUiState(
                lfoA = 0.5f,
                lfoB = 0.2f,
                mode = HyperLfoMode.AND,
                linkEnabled = false
            ),
            actions = LfoPanelActions(
                onLfoAChange = {},
                onLfoBChange = {},
                onModeChange = {},
                onLinkChange = {}
            )
        )
    }
}
