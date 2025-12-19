package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun QuadGroupPanel(
    groupName: String, // "1-4" or "5-8"
    pitch: Float,
    onPitchChange: (Float) -> Unit,
    sustain: Float,
    onSustainChange: (Float) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin())
                } else {
                    Modifier
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.deepPurple.copy(alpha = 0.5f),
                         SongeColors.deepPurple.copy(alpha = 0.8f) // Darker bottom
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                       SongeColors.neonMagenta.copy(alpha = 0.4f),
                       Color.Transparent,
                       Color.Black.copy(alpha = 0.5f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "GROUP $groupName",
            style = MaterialTheme.typography.labelMedium,
            color = SongeColors.neonMagenta
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            RotaryKnob(
                value = pitch,
                onValueChange = onPitchChange,
                label = "PITCH",
                size = 56.dp,
                progressColor = SongeColors.neonMagenta
            )
            
            RotaryKnob(
                value = sustain,
                onValueChange = onSustainChange,
                label = "SUSTAIN",
                size = 56.dp,
                progressColor = SongeColors.synthGreen
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
@Preview
fun QuadGroupPanelPreview() {
    MaterialTheme {
        QuadGroupPanel(
            groupName = "1-4",
            pitch = 0.5f,
            onPitchChange = {},
            sustain = 0.7f,
            onSustainChange = {},
            hazeState = null
        )
    }
}
