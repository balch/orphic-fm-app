package org.balch.songe.ui.panels

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.components.learnable
import org.balch.songe.ui.components.LocalLearnModeState
import org.balch.songe.input.MidiMappingState.Companion.ControlIds
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview

enum class HyperLfoMode { AND, OFF, OR }

/**
 * Hyper LFO Panel - Two LFOs combined with AND/OR logic
 * 
 * In "AND" mode: Both LFOs must be high for output
 * In "OR" mode: Either LFO high produces output
 */
@Composable
fun HyperLfoPanel(
    lfo1Rate: Float,
    onLfo1RateChange: (Float) -> Unit,
    lfo2Rate: Float,
    onLfo2RateChange: (Float) -> Unit,
    mode: HyperLfoMode,
    onModeChange: (HyperLfoMode) -> Unit,
    linkEnabled: Boolean,
    onLinkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val learnState = LocalLearnModeState.current
    val isActive = mode != HyperLfoMode.OFF
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(10.dp), clip = false)
            .clip(RoundedCornerShape(10.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.darkVoid.copy(alpha = 0.6f),
                        SongeColors.darkVoid.copy(alpha = 0.9f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        SongeColors.neonCyan.copy(alpha = 0.4f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.5f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Title with Activity Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Green activity indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isActive) SongeColors.synthGreen else Color(0xFF2A2A2A)
                    )
                    .border(
                        1.dp,
                        if (isActive) SongeColors.synthGreen.copy(alpha = 0.5f) else Color(0xFF3A3A3A),
                        RoundedCornerShape(4.dp)
                    )
            )
            
            Text(
                text = "HYPER LFO",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) SongeColors.neonCyan else SongeColors.neonCyan.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Controls Row
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = lfo1Rate,
                onValueChange = onLfo1RateChange,
                label = "FREQ A",
                controlId = ControlIds.HYPER_LFO_A,
                size = 48.dp,
                progressColor = if (isActive) SongeColors.neonCyan else SongeColors.neonCyan.copy(alpha = 0.4f)
            )
            RotaryKnob(
                value = lfo2Rate,
                onValueChange = onLfo2RateChange,
                label = "FREQ B",
                controlId = ControlIds.HYPER_LFO_B,
                size = 48.dp,
                progressColor = if (isActive) SongeColors.neonCyan else SongeColors.neonCyan.copy(alpha = 0.4f)
            )

            // 3-way AND/OFF/OR Switch
            Box(modifier = Modifier.learnable(ControlIds.HYPER_LFO_MODE, learnState)) {
                Vertical3WaySwitch(
                    topLabel = "AND",
                    middleLabel = "OFF",
                    bottomLabel = "OR",
                    position = when (mode) {
                        HyperLfoMode.AND -> 0
                        HyperLfoMode.OFF -> 1
                        HyperLfoMode.OR -> 2
                    },
                    onPositionChange = { pos ->
                        onModeChange(when (pos) {
                            0 -> HyperLfoMode.AND
                            1 -> HyperLfoMode.OFF
                            else -> HyperLfoMode.OR
                        })
                    },
                    color = SongeColors.neonCyan,
                    enabled = !learnState.isActive
                )
            }
            
            // LINK Vertical Switch - padded Box for better touch target
            Box(
                modifier = Modifier
                    .learnable(ControlIds.HYPER_LFO_LINK, learnState)
                    .padding(4.dp) // Expand touch target
            ) {
                VerticalToggle(
                    topLabel = "LINK",
                    bottomLabel = "OFF",
                    isTop = linkEnabled,
                    onToggle = { onLinkChange(it) },
                    color = SongeColors.electricBlue,
                    enabled = !learnState.isActive
                )
            }

        }
        
        Spacer(modifier = Modifier.weight(1f))
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
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) activeColor.copy(alpha = 0.8f) else Color(0xFF2A2A3A))
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
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp), // Increased vertical padding
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

        // Vertical switch track - Taller to match 3-way switch
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(56.dp) // Increased to match 3-way switch visual mass
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.2f))
                .let {
                    if (enabled) it.clickable { onToggle(!isTop) } else it
                },
            contentAlignment = if (isTop) Alignment.TopCenter else Alignment.BottomCenter
        ) {
            // Switch knob
            Box(
                modifier = Modifier
                    .padding(2.dp)
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
 * 3-way vertical switch for AND/OFF/OR selection.
 * position: 0 = top (AND), 1 = middle (OFF), 2 = bottom (OR)
 */
@Composable
private fun Vertical3WaySwitch(
    topLabel: String,
    middleLabel: String,
    bottomLabel: String,
    position: Int, // 0=top, 1=middle, 2=bottom
    onPositionChange: (Int) -> Unit,
    color: Color,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A2A))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
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
            modifier = Modifier
                .width(12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.2f))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press || event.type == PointerEventType.Move) {
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed) {
                                    val y = change.position.y
                                    val height = size.height
                                    
                                    // Calculate section (0, 1, 2)
                                    // 0 = Top, 1 = Middle, 2 = Bottom
                                    val section = (y / (height / 3)).toInt().coerceIn(0, 2)
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
                modifier = Modifier
                    .fillMaxWidth()
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
                    .background(if (position == 1) color.copy(alpha = 0.5f) else color)
            )
        }

        // Middle label (OFF) - clickable
        Text(
            middleLabel,
            fontSize = 6.sp,
            color = if (position == 1) color else color.copy(alpha = 0.4f),
            fontWeight = if (position == 1) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 8.sp,
            modifier = Modifier.clickable { onPositionChange(1) }
        )

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
fun HyperLfoPanelPreview() {
    MaterialTheme {
        HyperLfoPanel(
            lfo1Rate = 0.5f, 
            onLfo1RateChange = {}, 
            lfo2Rate = 0.2f, 
            onLfo2RateChange = {}, 
            mode = HyperLfoMode.AND, 
            onModeChange = {},
            linkEnabled = false,
            onLinkChange = {}
        )
    }
}
