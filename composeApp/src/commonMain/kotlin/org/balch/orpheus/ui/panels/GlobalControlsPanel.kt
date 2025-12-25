package org.balch.orpheus.ui.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.lfo.HyperLfoMode
import org.balch.orpheus.features.lfo.HyperLfoPanelLayout
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter


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
    liquidState: LiquidState? = LocalLiquidState.current,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            modifier.liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostLarge.dp,
                color = OrpheusColors.darkVoid,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
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
                    color = OrpheusColors.warmGlow
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotaryKnob(
                        value = delayTime,
                        onValueChange = onDelayTimeChange,
                        label = "TIME",
                        size = 40.dp,
                        progressColor = OrpheusColors.warmGlow
                    )
                    RotaryKnob(
                        value = delayFeedback,
                        onValueChange = onDelayFeedbackChange,
                        label = "FB",
                        size = 40.dp,
                        progressColor = OrpheusColors.warmGlow
                    )
                }
            }

            RotaryKnob(
                value = distortion,
                onValueChange = onDistortionChange,
                label = "DIST",
                size = 48.dp,
                progressColor = OrpheusColors.neonMagenta
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
                progressColor = OrpheusColors.synthGreen
            )
            RotaryKnob(
                value = pan,
                onValueChange = onPanChange,
                label = "PAN",
                size = 44.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            RotaryKnob(
                value = masterVolume,
                onValueChange = onMasterVolumeChange,
                label = "VOL",
                size = 52.dp,
                progressColor = OrpheusColors.electricBlue
            )
        }
    }
}

@Preview(widthDp = 800, heightDp = 240)
@Composable
fun GlobalControlsPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects, modifier = Modifier.size(900.dp, 300.dp)) {
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
                effects = effects
            )
        }
    }
}
