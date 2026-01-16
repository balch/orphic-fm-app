package org.balch.orpheus.features.resonator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.Learnable
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.learnable
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

private val RingsPanelColor = OrpheusColors.lakersGold

@Composable
fun ResonatorPanel(
    feature: ResonatorFeature = ResonatorViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "REZO",
        color = RingsPanelColor,
        expandedTitle = "Sonic Blender",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.weight(1f))
                // Combined Enable/Mode selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val segColors = SegmentedButtonDefaults.colors(
                        activeContainerColor = RingsPanelColor,
                        activeContentColor = OrpheusColors.lakersPurple,
                        inactiveContentColor = OrpheusColors.lakersGold,
                        inactiveContainerColor = OrpheusColors.lakersPurpleDark
                    )
                    Learnable(
                        controlId = "resonator_mode",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Regular modes (no "Off" option)
                            ResonatorMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ResonatorMode.entries.size
                                    ),
                                    onClick = {
                                        actions.setMode(mode)
                                    },
                                    selected = state.mode == mode,
                                    colors = segColors,
                                    icon = {}
                                ) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // Knobs row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val knobTrackColor = OrpheusColors.lakersPurpleDark
                    val knobProgressColor = RingsPanelColor
                    val knobColor = OrpheusColors.lakersGold
                    val labelColor = RingsPanelColor

                    RotaryKnob(state.structure, actions.setStructure, label = "STRUCT", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "resonator_structure")
                    RotaryKnob(state.brightness, actions.setBrightness, label = "BRIGHT", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "resonator_brightness")
                    RotaryKnob(state.damping, actions.setDamping, label = "DAMP", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "resonator_damping")
                    RotaryKnob(state.position, actions.setPosition, label = "POS", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "resonator_position")
                    RotaryKnob(state.mix, actions.setMix, label = "MIX", size = 40.dp, trackColor = knobTrackColor, progressColor = knobProgressColor, knobColor = knobColor, labelColor = labelColor, controlId = "resonator_mix")
                }

                // Target mix fader section
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "DRM",
                        fontSize = 10.sp,
                        color = RingsPanelColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HorizontalTargetMixFader(
                            value = state.targetMix,
                            onValueChange = actions.setTargetMix,
                            snapBack = state.snapBack,
                            accentColor = RingsPanelColor,
                            controlId = "resonator_target_mix"
                        )

                        SnapBackButton(
                            enabled = state.snapBack,
                            onClick = { actions.setSnapBack(!state.snapBack) },
                            accentColor = RingsPanelColor,
                            controlId = "resonator_snap_back"
                        )
                    }

                    Text(
                        "SYN",
                        fontSize = 10.sp,
                        color = RingsPanelColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HorizontalTargetMixFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    snapBack: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = OrpheusColors.lakersGold,
    controlId: String? = null
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)
    val density = LocalDensity.current
    val trackWidth = 160.dp
    val thumbWidth = 24.dp
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val thumbWidthPx = with(density) { thumbWidth.toPx() }
    val usableRange = (trackWidthPx - thumbWidthPx) / 2f
    
    val coroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    // Sync external value to animation state
    LaunchedEffect(value) {
        if (!isDragging) {
            val targetPx = (value - 0.5f) * 2f * usableRange
            animatedOffset.snapTo(targetPx)
        }
    }
    
    Box(
        modifier = modifier
            .size(trackWidth, 24.dp)
            .then(
                if (controlId != null) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            )
            .pointerInput(snapBack, isLearning) {
                if (isLearning) return@pointerInput
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            val newPos = (animatedOffset.value + dragAmount.x).coerceIn(-usableRange, usableRange)
                            animatedOffset.snapTo(newPos)
                            val newValue = 0.5f + (newPos / usableRange) * 0.5f
                            onValueChange(newValue.coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        if (snapBack) {
                            coroutineScope.launch {
                                animatedOffset.animateTo(0f, tween(300))
                                onValueChange(0.5f)
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        if (snapBack) {
                            coroutineScope.launch {
                                animatedOffset.animateTo(0f, tween(200))
                                onValueChange(0.5f)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Track
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.panelBackground.copy(alpha = 0.8f))
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .drawBehind {
                    val centerX = size.width / 2f
                    // Center notch
                    drawLine(
                        color = accentColor.copy(alpha = 0.5f),
                        start = Offset(centerX, -4f),
                        end = Offset(centerX, size.height + 4f),
                        strokeWidth = 2f
                    )
                }
        )
        
        // Thumb (Handle)
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.value.roundToInt(), 0) }
                .size(thumbWidth, 20.dp)
                .shadow(4.dp, RoundedCornerShape(4.dp), ambientColor = accentColor)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            OrpheusColors.metallicHighlight,
                            OrpheusColors.metallicSurface,
                            OrpheusColors.metallicShadow
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .drawBehind {
                    // Vertical grip line
                    drawLine(
                        color = accentColor.copy(alpha = 0.6f),
                        start = Offset(size.width / 2f, 4f),
                        end = Offset(size.width / 2f, size.height - 4f),
                        strokeWidth = 2f
                    )
                }
        )
    }
}

@Composable
private fun SnapBackButton(
    enabled: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    controlId: String? = null
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)

    Box(
        modifier = modifier.size(24.dp)
            .then(
                if (controlId != null) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            )
            .shadow(if (enabled) 1.dp else 4.dp, CircleShape, ambientColor = if (enabled) accentColor else Color.Black)
            .clip(CircleShape)
            .background(Brush.verticalGradient(if (enabled) listOf(OrpheusColors.lakersPurple, OrpheusColors.lakersPurpleDark) else listOf(OrpheusColors.metallicSurface, OrpheusColors.metallicShadow)))
            .border(1.5.dp, if (enabled) accentColor else accentColor.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = !isLearning) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (enabled) accentColor else OrpheusColors.charcoal).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape))
    }
}

@Preview
@Composable
fun ResonatorPanelPreview() {
    OrpheusTheme {
        ResonatorPanel(feature = ResonatorViewModel.previewFeature(ResonatorUiState()), isExpanded = true)
    }
}
