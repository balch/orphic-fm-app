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
                                    OrpheusColors.lightSteel,
                                    OrpheusColors.pureWhite,
                                    OrpheusColors.lightSteel
                                ) // Bright "Lit" Metal
                            } else {
                                listOf(
                                    OrpheusColors.slateGrey,
                                    OrpheusColors.greyShadow,
                                    OrpheusColors.mediumGrey
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
                                    OrpheusColors.brightSilver,
                                    OrpheusColors.deepCharcoal
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
            color = if (isHolding) OrpheusColors.almostBlack else OrpheusColors.silverGrey
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
