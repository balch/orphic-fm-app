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
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import org.balch.songe.features.lfo.HyperLfoMode
import org.balch.songe.features.lfo.HyperLfoPanelLayout
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview


@Composable
fun GlobalControlsPanel(
    // Hyper LFO
    modifier: Modifier = Modifier,
    vibrato: Float, // LFO1 Rate
    onVibratoChange: (Float) -> Unit,
    lfo2Rate: Float = 0.3f,
    onLfo2RateChange: (Float) -> Unit = {},
    hyperLfoMode: HyperLfoMode = HyperLfoMode.AND,
    onHyperLfoModeChange: (HyperLfoMode) -> Unit = {},
    // Effects
    distortion: Float,
    onDistortionChange: (Float) -> Unit,
    masterDrive: Float, // "Gain" like
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
    liquidState: LiquidState?,
) {
    val shape = RoundedCornerShape(12.dp)
    val effects = LocalLiquidEffects.current
    Row(
        modifier =
            modifier.shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false
            )
                .clip(shape)
                .then(
                    if (liquidState != null) {
                        Modifier.liquid(liquidState) {
                            frost = effects.frostMedium.dp
                            this.shape = shape
                            refraction = 0f
                            curve = 0f
                            tint = SongeColors.darkVoid.copy(alpha = effects.tintAlpha)
                            saturation = effects.saturation
                            contrast = effects.contrast
                        }
                    } else {
                        Modifier
                    }
                )
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    SongeColors.darkVoid.copy(
                                        alpha = 0.7f
                                    ),
                                    SongeColors.darkVoid.copy(
                                        alpha = 0.95f
                                    )
                                )
                        )
                )
                .border(
                    width = 1.dp,
                    brush =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    SongeColors.electricBlue.copy(
                                        alpha = 0.4f
                                    ),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f)
                                ),
                            start = Offset(0f, 0f),
                            end =
                                Offset(
                                    Float.POSITIVE_INFINITY,
                                    Float.POSITIVE_INFINITY
                                )
                        ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: Hyper LFO Section
        HyperLfoPanelLayout(
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
                Text(
                    "DELAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = SongeColors.warmGlow
                )
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
            liquidState = null
        )
    }
}
