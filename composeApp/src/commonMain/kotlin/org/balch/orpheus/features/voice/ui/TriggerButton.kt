package org.balch.orpheus.features.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.learnable

@Composable
fun TriggerButton(
    num: Int,
    isHolding: Boolean,
    onHoldChange: (Boolean) -> Unit,
    controlId: String? = null
) {
    val learnState = LocalLearnModeState.current
    val modifier =
        Modifier.width(42.dp) // Slightly wider
            .height(28.dp) // Match PulseButton size
            .then(
                if (controlId != null) Modifier.learnable(controlId, learnState)
                else Modifier
            )

    Box(
        modifier =
            modifier.clip(RoundedCornerShape(4.dp))
                .background(
                    // Metallic Gradient
                    Brush.verticalGradient(
                        colors =
                            if (isHolding) {
                                listOf(
                                    Color(0xFFD0D0D0),
                                    Color(0xFFFFFFFFF),
                                    Color(0xFFD0D0D0)
                                ) // Bright "Lit" Metal
                            } else {
                                listOf(
                                    Color(0xFF808080),
                                    Color(0xFF505050),
                                    Color(0xFF303030)
                                ) // Dark Metal
                            }
                    )
                )
                .border(
                    width = 1.dp,
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color(0xFFE0E0E0),
                                    Color(0xFF202020)
                                )
                        ),
                    shape = RoundedCornerShape(4.dp)
                )
                .clickable(enabled = !learnState.isActive) {
                    onHoldChange(!isHolding)
                },
        contentAlignment = Alignment.Center
    ) {
        // Text / Label
        Text(
            text = "$num",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHolding) Color(0xFF101010) else Color(0xFFAAAAAA)
        )

        // Active Indicator (LED style)
        if (isHolding) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(
                            OrpheusColors.electricBlue.copy(alpha = 0.2f)
                        ) // Blue tint overlay
            )
        }
    }
}
