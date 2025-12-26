package org.balch.orpheus.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.timeline.TweakTimelineUiState
import org.balch.orpheus.features.timeline.TweakTimelineViewModel
import org.balch.orpheus.features.timeline.ui.CompactTweakTimelineView
import org.balch.orpheus.features.timeline.ui.ExpandedTweakTimelineScreen
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.panels.compact.CompactDuoLiquidPanel
import org.balch.orpheus.ui.panels.compact.CompactLandscapeHeaderPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizUiState
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.widgets.RotaryKnob
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
    timelineViewModel: TweakTimelineViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val vizState by vizViewModel.uiState.collectAsState()
    val timelineState by timelineViewModel.uiState.collectAsState()

    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    
    Box(modifier = modifier.fillMaxSize()) {
        CompactLandscapeLayout(
            modifier = Modifier.fillMaxSize(),
            presetState = presetState,
            voiceState = voiceState,
            vizState = vizState,
            timelineState = timelineState,
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
            onMasterVolumeChange = { v -> voiceViewModel.onMasterVolumeChange(v) },
            onTimelinePlayPause = { timelineViewModel.togglePlayPause() },
            onTimelineStop = { timelineViewModel.stop() },
            onTimelineExpand = { timelineViewModel.expand() },
            onKeyEvent = { event, isDialogActive ->
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    voiceState = voiceState,
                    voiceViewModel = voiceViewModel,
                    isDialogActive = isDialogActive
                )
            }
        )
        
        // Expanded Timeline Screen Overlay
        if (timelineState.isExpanded) {
            ExpandedTweakTimelineScreen(
                onDismiss = { },
                viewModel = timelineViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun CompactLandscapeLayout(
    modifier: Modifier = Modifier,
    presetState: PresetUiState,
    voiceState: VoiceUiState,
    vizState: VizUiState,
    timelineState: TweakTimelineUiState,
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
    onMasterVolumeChange: (Float) -> Unit,
    onTimelinePlayPause: () -> Unit,
    onTimelineStop: () -> Unit,
    onTimelineExpand: () -> Unit,
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
            .padding(4.dp)
    ) {
        // Top bar: Patch and Viz dropdowns
            CompactLandscapeHeaderPanel(
                selectedPresetName = presetState.selectedPreset?.name ?: "Init Patch",
                presets = presetState.presets,
                presetDropdownExpanded = presetDropdownExpanded,
                onPresetDropdownExpandedChange = { presetDropdownExpanded = it },
                onPresetSelect = onPresetSelect,
                selectedVizName = vizState.selectedViz.name,
                visualizations = vizState.visualizations,
                vizDropdownExpanded = vizDropdownExpanded,
                onVizDropdownExpandedChange = { vizDropdownExpanded = it },
                onVizSelect = onVizSelect,
                masterVolume = voiceState.masterVolume,
                peakLevel = voiceState.peakLevel,
                onMasterVolumeChange = onMasterVolumeChange,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.fillMaxWidth()
            )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Quad 1 Knobs (Left End)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotaryKnob(
                    value = voiceState.quadGroupPitches[0],
                    onValueChange = { onQuadPitchChange(0, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[0],
                    onValueChange = { onQuadHoldChange(0, it) },
                    label = "Hold",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
            }


            CompactTweakTimelineView(
                state = timelineState.timeline,
                onPlayPause = onTimelinePlayPause,
                onStop = onTimelineStop,
                onExpand = onTimelineExpand,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            // Quad 2 Knobs (Right End)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotaryKnob(
                    value = voiceState.quadGroupPitches[1],
                    onValueChange = { onQuadPitchChange(1, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.synthGreen
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[1],
                    onValueChange = { onQuadHoldChange(1, it) },
                    label = "Hold",
                    size = 38.dp,
                    progressColor = OrpheusColors.synthGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom row: Four Duo panels in a row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Duo 1-2: Magenta Border, Cyan Accents
            CompactDuoLiquidPanel(
                pairIndex = 0,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                borderColor = OrpheusColors.neonMagenta,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 3-4: Electric Blue Border, Cyan Accents
            CompactDuoLiquidPanel(
                pairIndex = 1,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                borderColor = OrpheusColors.electricBlue,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 5-6: Green Border, Orange Accents (matching uploaded_image_1766749385773.png)
            CompactDuoLiquidPanel(
                pairIndex = 2,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                borderColor = OrpheusColors.neonOrange,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 7-8: Green Border, Orange Accents
            CompactDuoLiquidPanel(
                pairIndex = 3,
                voiceStates = voiceState.voiceStates,
                voiceEnvelopeSpeeds = voiceState.voiceEnvelopeSpeeds,
                onVoiceTuneChange = onVoiceTuneChange,
                onEnvelopeSpeedChange = onEnvelopeSpeedChange,
                onPulseStart = onPulseStart,
                onPulseEnd = onPulseEnd,
                onVoiceHoldChange = onVoiceHoldChange,
                borderColor = OrpheusColors.synthGreen,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
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
                timelineState = TweakTimelineUiState(),
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
                onMasterVolumeChange = { _ -> },
                onTimelinePlayPause = {},
                onTimelineStop = {},
                onTimelineExpand = {},
                onKeyEvent = { _, _ -> false }
            )
        }
    }
}
