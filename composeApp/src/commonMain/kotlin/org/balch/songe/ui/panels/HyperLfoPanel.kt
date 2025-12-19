package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
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
    linkEnabled: Boolean,
    onLinkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
        verticalArrangement = Arrangement.Top // Changed from SpaceBetween
    ) {
        // Title at TOP
        Text(
            text = "HYPER LFO",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = SongeColors.neonCyan
        )
        
        Spacer(modifier = Modifier.weight(1f)) // Push content to center
        
        // Controls Row
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically // Center align items vertically
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

            // AND/OR Vertical Switch
            VerticalToggle(
                topLabel = "AND",
                bottomLabel = "OR",
                isTop = mode == HyperLfoMode.AND,
                onToggle = { isAnd -> onModeChange(if (isAnd) HyperLfoMode.AND else HyperLfoMode.OR) },
                color = SongeColors.neonCyan
            )
            
            // LINK Vertical Switch
            VerticalToggle(
                topLabel = "LINK",
                bottomLabel = "OFF",
                isTop = linkEnabled,
                onToggle = { onLinkChange(it) },
                color = SongeColors.electricBlue
            )

        }
        
        Spacer(modifier = Modifier.weight(1f)) // Push content to center
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
    color: Color
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

        // Vertical switch track
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(40.dp) // Match height of rotary knob stack approx
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.2f))
                .clickable { onToggle(!isTop) },
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
            lineHeight = 9.sp // Explicit line height
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
