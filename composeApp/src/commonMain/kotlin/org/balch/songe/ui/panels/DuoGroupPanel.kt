package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import org.balch.songe.ui.components.LfoSwitch
import org.balch.songe.ui.components.LfoWaveform
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DuoGroupPanel(
    voices: Pair<Int, Int>, // Indices of the voices being modulated (e.g. 0 to 1)
    modDepth: Float,
    onModDepthChange: (Float) -> Unit,
    lfoEnabled: Boolean,
    onLfoEnabledChange: (Boolean) -> Unit,
    lfoWaveform: LfoWaveform,
    onLfoWaveformChange: (LfoWaveform) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
             .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp), clip = false)
             .clip(RoundedCornerShape(12.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
                } else {
                    Modifier
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SongeColors.darkVoid.copy(alpha = 0.4f),
                        SongeColors.darkVoid.copy(alpha = 0.7f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        SongeColors.neonCyan.copy(alpha = 0.3f),
                        Color.Transparent,
                         Color.Black.copy(alpha = 0.5f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${voices.first + 1}-${voices.second + 1} MOD",
            style = MaterialTheme.typography.labelSmall,
            color = SongeColors.neonCyan
        )
        
        RotaryKnob(
            value = modDepth,
            onValueChange = onModDepthChange,
            label = "DEPTH",
            size = 48.dp,
            progressColor = SongeColors.neonCyan,
            knobColor = SongeColors.deepPurple
        )
        
        LfoSwitch(
            enabled = lfoEnabled,
            onEnabledChange = onLfoEnabledChange,
            waveform = lfoWaveform,
            onWaveformChange = onLfoWaveformChange,
            activeColor = SongeColors.neonCyan
        )
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
@Preview
fun DuoGroupPanelPreview() {
    MaterialTheme {
        DuoGroupPanel(
            voices = 0 to 1,
            modDepth = 0.3f,
            onModDepthChange = {},
            lfoEnabled = true,
            onLfoEnabledChange = {},
            lfoWaveform = LfoWaveform.TRIANGLE,
            onLfoWaveformChange = {},
            hazeState = null
        )
    }
}
