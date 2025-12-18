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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
fun GlobalControlsPanel(
    vibrato: Float,
    onVibratoChange: (Float) -> Unit,
    distortion: Float,
    onDistortionChange: (Float) -> Unit,
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    pan: Float,
    onPanChange: (Float) -> Unit,
    masterDrive: Float,
    onMasterDriveChange: (Float) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.regular())
                } else {
                    Modifier
                }
            )
             .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.darkVoid.copy(alpha = 0.7f),
                        SongeColors.darkVoid.copy(alpha = 0.95f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        SongeColors.electricBlue.copy(alpha = 0.5f),
                        Color.Transparent,
                         Color.Black.copy(alpha = 0.6f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GLOBAL CONTROLS",
            style = MaterialTheme.typography.titleMedium,
            color = SongeColors.electricBlue,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RotaryKnob(
                value = vibrato,
                onValueChange = onVibratoChange,
                label = "VIBRATO",
                size = 64.dp,
                progressColor = SongeColors.neonCyan
            )
            
            RotaryKnob(
                value = distortion,
                onValueChange = onDistortionChange,
                label = "DIST",
                size = 64.dp,
                progressColor = SongeColors.warmGlow
            )
            
            RotaryKnob(
                value = masterDrive,
                onValueChange = onMasterDriveChange,
                label = "DRIVE",
                size = 64.dp,
                progressColor = SongeColors.warmGlow
            )
             
            RotaryKnob(
                value = pan,
                onValueChange = onPanChange,
                label = "PAN",
                size = 64.dp,
                progressColor = SongeColors.neonMagenta
            )
            
            RotaryKnob(
                value = masterVolume,
                onValueChange = onMasterVolumeChange,
                label = "MAIN VOL",
                size = 72.dp,
                progressColor = SongeColors.electricBlue
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
@Preview
fun GlobalControlsPanelPreview() {
    MaterialTheme {
        GlobalControlsPanel(
            vibrato = 0.2f,
            onVibratoChange = {},
            distortion = 0.4f,
            onDistortionChange = {},
            masterVolume = 0.8f,
            onMasterVolumeChange = {},
            pan = 0.5f,
            onPanChange = {},
            masterDrive = 0.3f,
            onMasterDriveChange = {},
            hazeState = null
        )
    }
}
