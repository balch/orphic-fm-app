package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

@Composable
@Preview
fun HoldButtonPreview() {
    OrpheusTheme {
        HoldSwitch(checked = true, onCheckedChange = {})
    }
}

enum class SwitchOrientation {
    Horizontal,
    Vertical
}

/**
 * A toggle switch with a custom 3D skeuomorphic design.
 */
@Composable
fun HoldSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = OrpheusColors.synthGreen,
    orientation: SwitchOrientation = SwitchOrientation.Horizontal,
    size: DpSize? = null
) {
    val defaultSize = if (orientation == SwitchOrientation.Horizontal) {
        DpSize(48.dp, 24.dp)
    } else {
        DpSize(24.dp, 48.dp)
    }
    
    val actualSize = size ?: defaultSize

    // Custom 3D Switch Container
    Box(
        modifier = modifier
            .size(actualSize)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.8f),
                        OrpheusColors.darkVoid
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onCheckedChange(!checked) }
    ) {
        // Thumb (The moving part)
        Box(
            modifier = Modifier
                .align(
                    if (orientation == SwitchOrientation.Horizontal) {
                        if (checked) Alignment.CenterEnd else Alignment.CenterStart
                    } else {
                        if (checked) Alignment.TopCenter else Alignment.BottomCenter
                    }
                )
                .padding(2.dp)
                .size(24.dp)
                .shadow(4.dp, CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (checked) {
                            listOf(Color.White, activeColor)
                        } else {
                            listOf(Color.Gray, Color.DarkGray)
                        }
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        )
    }
}
