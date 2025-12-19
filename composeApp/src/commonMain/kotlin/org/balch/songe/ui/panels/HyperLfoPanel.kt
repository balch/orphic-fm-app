package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeDefaults.style
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview
import java.awt.SystemColor.text

enum class HyperLfoMode { AND, OR }

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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
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
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title at TOP
        Text(
            text = "HYPER LFO",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = SongeColors.neonCyan
        )
        
        // AND/OR Toggle in MIDDLE
        Row(
            modifier = Modifier
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeToggleButton(
                modifier = Modifier.width(42.dp).size(28.dp),
                text = "AND",
                isSelected = mode == HyperLfoMode.AND,
                onClick = { onModeChange(HyperLfoMode.AND) },
                activeColor = SongeColors.neonCyan
            )
            ModeToggleButton(
                modifier = Modifier.width(42.dp).size(28.dp),
                text = "OR",
                isSelected = mode == HyperLfoMode.OR,
                onClick = { onModeChange(HyperLfoMode.OR) },
                activeColor = SongeColors.neonMagenta
            )
        }

        // LFO Rate Knobs at BOTTOM
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RotaryKnob(
                value = lfo1Rate,
                onValueChange = onLfo1RateChange,
                label = "FREQ A",
                size = 48.dp,
                progressColor = SongeColors.neonCyan
            )
            RotaryKnob(
                value = lfo2Rate,
                onValueChange = onLfo2RateChange,
                label = "FREQ B",
                size = 48.dp,
                progressColor = SongeColors.neonCyan
            )
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

@Preview
@Composable
fun HyperLfoPanelPreview() {
    MaterialTheme {
        HyperLfoPanel(lfo1Rate = 0.5f, onLfo1RateChange = {}, lfo2Rate = 0.2f, onLfo2RateChange = {}, mode = HyperLfoMode.AND, onModeChange = {})
    }
}
