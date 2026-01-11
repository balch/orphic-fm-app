package org.balch.orpheus.features.tweaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.CrossModSelector
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

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
        fillMaxHeight = true
    ) {
        ModTweaksContent(voiceFeature = voiceFeature)
    }
}

@Composable
private fun ModTweaksContent(
    voiceFeature: SynthFeature<VoiceUiState, VoicePanelActions>,
    modifier: Modifier = Modifier,
) {
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val actions = voiceFeature.actions

    Column(
        modifier = modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Vertical centering using weighted spacers
        Spacer(modifier = Modifier.weight(1f))

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

        Spacer(modifier = Modifier.weight(1.2f))
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 360, heightDp = 300)
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
