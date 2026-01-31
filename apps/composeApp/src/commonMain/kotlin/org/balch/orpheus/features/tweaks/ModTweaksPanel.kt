package org.balch.orpheus.features.tweaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.drum.DrumTriggerSource
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.CrossModSelector
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.ValueCycleButton

/**
 * Mod Tweaks Panel - Compact view for modulation and voice controls.
 * Reorganized into a horizontal layout for knobs to better utilize space in portrait mode.
 */
@Composable
fun ModTweaksPanel(
    voiceFeature: SynthFeature<VoiceUiState, VoicePanelActions>,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    CollapsibleColumnPanel(
        modifier = modifier,
        title = "MOD",
        color = OrpheusColors.electricBlue,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        expandedTitle = "System Tweaker",
        showCollapsedHeader = showCollapsedHeader,
    ) {

        val voiceState by voiceFeature.stateFlow.collectAsState()
        val actions = voiceFeature.actions

        // FM Structure Selector (Centered at top)
        CrossModSelector(
            isCrossQuad = voiceState.fmStructureCrossQuad,
            onToggle = actions.onFmStructureChange
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Modulation Controls Row - Replaces the sparse vertical column with a balanced row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            RotaryKnob(
                value = voiceState.bpm.toFloat(),
                onValueChange = { actions.onBpmChange(it.toDouble()) },
                label = "BPM",
                range = 60f..200f,
                controlId = ControlIds.BPM,
                size = 52.dp,
                progressColor = OrpheusColors.neonMagenta
            )

            RotaryKnob(
                value = voiceState.totalFeedback,
                onValueChange = actions.onTotalFeedbackChange,
                label = "\u221E\u221E", // infinity",
                controlId = ControlIds.TOTAL_FEEDBACK,
                size = 52.dp,
                progressColor = OrpheusColors.neonCyan
            )

            RotaryKnob(
                value = voiceState.vibrato,
                onValueChange = actions.onVibratoChange,
                label = "VIB",
                controlId = ControlIds.VIBRATO,
                size = 52.dp,
                progressColor = OrpheusColors.neonMagenta
            )

            RotaryKnob(
                value = voiceState.voiceCoupling,
                onValueChange = actions.onVoiceCouplingChange,
                label = "COUPLING",
                controlId = ControlIds.VOICE_COUPLING,
                size = 52.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quad Trigger Sources
        Text(
            text = "QUAD TRIGGERS",
            color = OrpheusColors.electricBlue,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Q1", "Q2", "Q3").forEachIndexed { index, label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    ValueCycleButton(
                        value = DrumTriggerSource.values().getOrElse(voiceState.quadTriggerSources.getOrElse(index) { 0 }) { DrumTriggerSource.INTERNAL },
                        values = DrumTriggerSource.values().toList(),
                        onValueChange = { src -> actions.onQuadTriggerSourceChange(index, src.ordinal) },
                        modifier = Modifier.height(24.dp),
                        labelProvider = { src ->
                            when (src) {
                                DrumTriggerSource.INTERNAL -> "INT"
                                DrumTriggerSource.FLUX_T1 -> "T1"
                                DrumTriggerSource.FLUX_T2 -> "T2"
                                DrumTriggerSource.FLUX_T3 -> "T3"
                            }
                        }
                    )
                }
            }
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun ModTweaksPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        ModTweaksPanel(
            voiceFeature = VoiceViewModel.previewFeature(),
            isExpanded = true,
            showCollapsedHeader = false
        )
    }
}
