package org.balch.songe.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlobalControlsPanel(
    // Hyper LFO
    vibrato: Float, // LFO1 Rate
    onVibratoChange: (Float) -> Unit,
    lfo2Rate: Float = 0.3f,
    onLfo2RateChange: (Float) -> Unit = {},
    hyperLfoMode: org.balch.songe.features.lfo.HyperLfoMode = _root_ide_package_.org.balch.songe.features.lfo.HyperLfoMode.AND,
    onHyperLfoModeChange: (org.balch.songe.features.lfo.HyperLfoMode) -> Unit = {},
    // Effects
    distortion: Float,
    onDistortionChange: (Float) -> Unit,
    masterDrive: Float,  // "Gain" like
    onMasterDriveChange: (Float) -> Unit,
    // Delay (new params - can be hooked later)
    delayTime: Float = 0.3f,
    onDelayTimeChange: (Float) -> Unit = {},
    delayFeedback: Float = 0.4f,
    onDelayFeedbackChange: (Float) -> Unit = {},
    // Master output
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    pan: Float,
    onPanChange: (Float) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
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
                        SongeColors.electricBlue.copy(alpha = 0.4f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.6f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: Hyper LFO Section
        _root_ide_package_.org.balch.songe.features.lfo.HyperLfoPanel(
            lfo1Rate = vibrato,
            onLfo1RateChange = onVibratoChange,
            lfo2Rate = lfo2Rate,
            onLfo2RateChange = onLfo2RateChange,
            mode = hyperLfoMode,
            onModeChange = onHyperLfoModeChange,
            linkEnabled = false, // TODO: Expose this up if needed
            onLinkChange = {}
        )

        Spacer(modifier = Modifier.width(8.dp))

        // CENTER: Mode & Delay Section
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DELAY", style = MaterialTheme.typography.labelSmall, color = SongeColors.warmGlow)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotaryKnob(
                        value = delayTime,
                        onValueChange = onDelayTimeChange,
                        label = "TIME",
                        size = 40.dp,
                        progressColor = SongeColors.warmGlow
                    )
                    RotaryKnob(
                        value = delayFeedback,
                        onValueChange = onDelayFeedbackChange,
                        label = "FB",
                        size = 40.dp,
                        progressColor = SongeColors.warmGlow
                    )
                }
            }
            
            RotaryKnob(
                value = distortion,
                onValueChange = onDistortionChange,
                label = "DIST",
                size = 48.dp,
                progressColor = SongeColors.neonMagenta
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // RIGHT: Master Output Section
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = masterDrive,
                onValueChange = onMasterDriveChange,
                label = "GAIN",
                size = 44.dp,
                progressColor = SongeColors.synthGreen
            )
            RotaryKnob(
                value = pan,
                onValueChange = onPanChange,
                label = "PAN",
                size = 44.dp,
                progressColor = SongeColors.neonMagenta
            )
            RotaryKnob(
                value = masterVolume,
                onValueChange = onMasterVolumeChange,
                label = "VOL",
                size = 52.dp,
                progressColor = SongeColors.electricBlue
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
@Preview(widthDp = 800, heightDp = 240)
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
