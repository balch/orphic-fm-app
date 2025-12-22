package org.balch.songe.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.theme.SongeColors
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun HoldButtonPreview() {
    MaterialTheme {
        HoldButton(checked = true, onCheckedChange = {})
    }
}

/**
 * A toggle switch with a custom 3D skeuomorphic design.
 */
@Composable
fun HoldButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "HOLD",
    activeColor: Color = SongeColors.synthGreen
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Custom 3D Switch Container
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                         colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            SongeColors.darkVoid
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
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
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
        
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (checked) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
