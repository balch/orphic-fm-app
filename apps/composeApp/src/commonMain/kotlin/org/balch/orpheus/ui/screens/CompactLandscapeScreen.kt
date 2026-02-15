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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.SynthFeature
import org.balch.orpheus.core.toFeature
import org.balch.orpheus.features.draw.DrawSequencerFeature
import org.balch.orpheus.features.draw.DrawSequencerViewModel
import org.balch.orpheus.features.draw.ui.CompactDrawSequencerView
import org.balch.orpheus.features.draw.ui.ExpandedDrawSequencerScreen
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.speech.SpeechViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.visualizations.preview.LiquidEffectsProvider
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.panels.compact.CompactDuoLiquidPanel
import org.balch.orpheus.ui.panels.compact.CompactLandscapeHeaderPanel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob

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
    features: Set<SynthFeature<*, *>>,
) {
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current

    val voiceFeature: VoicesFeature = features.toFeature()
    val presetsFeature: PresetsFeature = features.toFeature()
    val vizFeature: VizFeature = features.toFeature()
    val sequencerFeature: DrawSequencerFeature = features.toFeature()

    val keyActions = rememberSynthKeyActions(features)

    Box(modifier = modifier.fillMaxSize()) {
        CompactLandscapeLayout(
            modifier = Modifier.fillMaxSize(),
            presetFeature = presetsFeature,
            voiceFeature = voiceFeature,
            vizFeature = vizFeature,
            sequencerFeature = sequencerFeature,
            liquidState = liquidState,
            effects = effects,
            onKeyEvent = { event, isDialogActive ->
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    isDialogActive = isDialogActive,
                    keyActions = keyActions,
                )
            }
        )

        // Expanded Sequencer Screen Overlay
        val sequencerState by sequencerFeature.stateFlow.collectAsState()
        val sequencerActions = sequencerFeature.actions
        if (sequencerState.isExpanded) {
            Dialog(
                onDismissRequest = { sequencerActions.onCancel() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                ExpandedDrawSequencerScreen(
                    sequencerFeature = sequencerFeature,
                    onDismiss = { sequencerActions.onCancel() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}


@Composable
private fun CompactLandscapeLayout(
    modifier: Modifier = Modifier,
    presetFeature: PresetsFeature = PresetsViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    sequencerFeature: DrawSequencerFeature = DrawSequencerViewModel.feature(),
    liquidState: LiquidState? = null,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current,
    onKeyEvent: (KeyEvent, Boolean) -> Boolean = { _, _ -> false },
) {
    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Dropdown states
    var presetDropdownExpanded by remember { mutableStateOf(false) }
    var vizDropdownExpanded by remember { mutableStateOf(false) }

    val voiceState by voiceFeature.stateFlow.collectAsState()
    val voiceActions = voiceFeature.actions

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
                presetFeature = presetFeature,
                vizFeature = vizFeature,
                voiceFeature = voiceFeature,
                presetDropdownExpanded = presetDropdownExpanded,
                onPresetDropdownExpandedChange = { presetDropdownExpanded = it },
                vizDropdownExpanded = vizDropdownExpanded,
                onVizDropdownExpandedChange = { vizDropdownExpanded = it },
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .padding(end = 8.dp),
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
                    onValueChange = { voiceActions.setQuadPitch(0, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[0],
                    onValueChange = { voiceActions.setQuadHold(0, it) },
                    label = "Hold",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
            }

            CompactDrawSequencerView(
                sequencerFeature = sequencerFeature,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.2f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
            )

            // Quad 2 Knobs (Right End)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotaryKnob(
                    value = voiceState.quadGroupPitches[1],
                    onValueChange = { voiceActions.setQuadPitch(1, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.synthGreen
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[1],
                    onValueChange = { voiceActions.setQuadHold(1, it) },
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
                voiceFeature = voiceFeature,
                borderColor = OrpheusColors.neonMagenta,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 3-4: Electric Blue Border, Cyan Accents
            CompactDuoLiquidPanel(
                pairIndex = 1,
                voiceFeature = voiceFeature,
                borderColor = OrpheusColors.electricBlue,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 5-6: Green Border, Orange Accents (matching uploaded_image_1766749385773.png)
            CompactDuoLiquidPanel(
                pairIndex = 2,
                voiceFeature = voiceFeature,
                borderColor = OrpheusColors.neonOrange,
                liquidState = liquidState,
                effects = effects,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // Duo 7-8: Green Border, Orange Accents
            CompactDuoLiquidPanel(
                pairIndex = 3,
                voiceFeature = voiceFeature,
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
                presetFeature = PresetsViewModel.previewFeature(),
                voiceFeature = VoiceViewModel.previewFeature(),
                vizFeature = VizViewModel.previewFeature(),
                sequencerFeature = DrawSequencerViewModel.previewFeature(),
                liquidState = liquidState,
                effects = effects,
                onKeyEvent = { _, _ -> false }
            )
        }
    }
}
