package org.balch.orpheus.ui.compact

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.compact.widgets.CompactLandscapeHeaderPanel
import org.balch.orpheus.ui.compact.widgets.CompactQuadPanel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizUiState
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

/**
 * Compact Landscape Layout: Instrument-style design for mobile landscape.
 * 
 * Layout:
 * - Top: Patch and Viz dropdowns
 * - Left: Quad 1 (voices 1-2 top, 3-4 bottom)
 * - Center: Quad knobs (Pitch/Hold for each quad)
 * - Right: Quad 2 (voices 5-6 top, 7-8 bottom)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLandscapeScreen(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
    vizViewModel: VizViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val vizState by vizViewModel.uiState.collectAsState()

    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    
    CompactLandscapeLayout(
        modifier = modifier,
        presetState = presetState,
        voiceState = voiceState,
        vizState = vizState,
        liquidState = liquidState,
        effects = effects,
        onPresetSelect = { presetViewModel.applyPreset(it) },
        onVizSelect = { vizViewModel.selectVisualization(it) },
        onVoiceTuneChange = { i, v -> voiceViewModel.onVoiceTuneChange(i, v) },
        onEnvelopeSpeedChange = { i, v -> voiceViewModel.onVoiceEnvelopeSpeedChange(i, v) },
        onPulseStart = { i -> voiceViewModel.onPulseStart(i) },
        onPulseEnd = { i -> voiceViewModel.onPulseEnd(i) },
        onVoiceHoldChange = { i, h -> voiceViewModel.onHoldChange(i, h) },
        onQuadPitchChange = { i, v -> voiceViewModel.onQuadPitchChange(i, v) },
        onQuadHoldChange = { i, v -> voiceViewModel.onQuadHoldChange(i, v) },
        onKeyEvent = { event, isDialogActive ->
            SynthKeyboardHandler.handleKeyEvent(
                keyEvent = event,
                voiceState = voiceState,
                voiceViewModel = voiceViewModel,
                isDialogActive = isDialogActive
            )
        }
    )
}

@Composable
private fun CompactLandscapeLayout(
    modifier: Modifier = Modifier,
    presetState: PresetUiState,
    voiceState: VoiceUiState,
    vizState: VizUiState,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    onPresetSelect: (org.balch.orpheus.core.presets.DronePreset) -> Unit,
    onVizSelect: (org.balch.orpheus.ui.viz.Visualization) -> Unit,
    onVoiceTuneChange: (Int, Float) -> Unit,
    onEnvelopeSpeedChange: (Int, Float) -> Unit,
    onPulseStart: (Int) -> Unit,
    onPulseEnd: (Int) -> Unit,
    onVoiceHoldChange: (Int, Boolean) -> Unit,
    onQuadPitchChange: (Int, Float) -> Unit,
    onQuadHoldChange: (Int, Float) -> Unit,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent, Boolean) -> Boolean,
) {
    
    val shape = RoundedCornerShape(12.dp)

    
    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Dropdown states
    var presetDropdownExpanded by remember { mutableStateOf(false) }
    var vizDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                onKeyEvent(event, presetDropdownExpanded || vizDropdownExpanded)
            }
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(4.dp)
    ) {
        // Top bar: Patch and Viz dropdowns
        CompactLandscapeHeaderPanel(
            selectedPresetName = presetState.selectedPreset?.name ?: "Patch",
            presets = presetState.presets,
            presetDropdownExpanded = presetDropdownExpanded,
            onPresetDropdownExpandedChange = { presetDropdownExpanded = it },
            onPresetSelect = onPresetSelect,
            selectedVizName = vizState.selectedViz.name,
            visualizations = vizState.visualizations,
            vizDropdownExpanded = vizDropdownExpanded,
            onVizDropdownExpandedChange = { vizDropdownExpanded = it },
            onVizSelect = onVizSelect
        )

        // Main content: Left Quad with Knobs | Right Quad with Knobs
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Quad 1 (voices 1-4 + knobs)
            CompactQuadPanel(
                quadIndex = 0,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                quadPitch = voiceState.quadGroupPitches[0],
                quadHold = voiceState.quadGroupHolds[0],
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                onQuadPitchChange = { onQuadPitchChange(0, it) },
                onQuadHoldChange = { onQuadHoldChange(0, it) },
                color = OrpheusColors.neonMagenta,
                knobsOnLeft = false
            )

            // Right side: Quad 2 (knobs + voices 5-8)
            CompactQuadPanel(
                quadIndex = 1,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                quadPitch = voiceState.quadGroupPitches[1],
                quadHold = voiceState.quadGroupHolds[1],
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                onQuadPitchChange = { onQuadPitchChange(1, it) },
                onQuadHoldChange = { onQuadHoldChange(1, it) },
                color = OrpheusColors.synthGreen,
                knobsOnLeft = true
            )
        }
    }
}


// ==================== PREVIEWS ====================

@Preview(widthDp = 800, heightDp = 400)
@Composable
private fun CompactLandscapeLayoutPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(
        effects = effects,
        modifier = Modifier.fillMaxSize()
    ) {
        val liquidState = LocalLiquidState.current
        if (liquidState != null) {
            CompactLandscapeLayout(
                presetState = PresetUiState(),
                voiceState = VoiceUiState(),
                vizState = VizUiState(
                    selectedViz = org.balch.orpheus.features.viz.OffViz(),
                    visualizations = listOf(org.balch.orpheus.features.viz.OffViz()),
                    showKnobs = false
                ),
                liquidState = liquidState,
                effects = effects,
                onPresetSelect = {},
                onVizSelect = {},
                onVoiceTuneChange = { _, _ -> },
                onEnvelopeSpeedChange = { _, _ -> },
                onPulseStart = {},
                onPulseEnd = {},
                onVoiceHoldChange = { _, _ -> },
                onQuadPitchChange = { _, _ -> },
                onQuadHoldChange = { _, _ -> },
                onKeyEvent = { _, _ -> false }
            )
        }
    }
}
