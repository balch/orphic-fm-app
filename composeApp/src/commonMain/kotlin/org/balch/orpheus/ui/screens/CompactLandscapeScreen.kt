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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.tweaker.TweakSequencerFeature
import org.balch.orpheus.features.tweaker.TweakSequencerViewModel
import org.balch.orpheus.features.tweaker.ui.CompactTweakSequencerView
import org.balch.orpheus.features.tweaker.ui.ExpandedTweakSequencerScreen
import org.balch.orpheus.features.viz.VizFeature
import org.balch.orpheus.features.viz.VizViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.panels.compact.CompactDuoLiquidPanel
import org.balch.orpheus.ui.panels.compact.CompactLandscapeHeaderPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
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
    sequencerViewModel: TweakSequencerViewModel = metroViewModel(),
) {
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current

    Box(modifier = modifier.fillMaxSize()) {
        CompactLandscapeLayout(
            modifier = Modifier.fillMaxSize(),
            presetFeature = presetViewModel,
            voiceFeature = voiceViewModel,
            vizFeature = vizViewModel,
            sequencerFeature = sequencerViewModel,
            liquidState = liquidState,
            effects = effects,
            onKeyEvent = { event, isDialogActive ->
                // Still using ViewModel instance here as SynthKeyboardHandler expects it.
                // Ideally SynthKeyboardHandler should be refactored too, but for now this works 
                // as the ViewModel is available in the screen scope.
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    voiceFeature = voiceViewModel,
                    isDialogActive = isDialogActive
                )
            }
        )
        
        // Expanded Sequencer Screen Overlay
        val sequencerState by sequencerViewModel.stateFlow.collectAsState()
        val sequencerActions = sequencerViewModel.actions
        if (sequencerState.isExpanded) {
            Dialog(
                onDismissRequest = { sequencerActions.onCancel() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                ExpandedTweakSequencerScreen(
                    sequencerFeature = sequencerViewModel,
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
    presetFeature: PresetsFeature,
    voiceFeature: VoicesFeature,
    vizFeature: VizFeature,
    sequencerFeature: TweakSequencerFeature,
    liquidState: LiquidState?,
    effects: VisualizationLiquidEffects,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent, Boolean) -> Boolean,
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
                    onValueChange = { voiceActions.onQuadPitchChange(0, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[0],
                    onValueChange = { voiceActions.onQuadHoldChange(0, it) },
                    label = "Hold",
                    size = 38.dp,
                    progressColor = OrpheusColors.neonMagenta
                )
            }

            CompactTweakSequencerView(
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
                    onValueChange = { voiceActions.onQuadPitchChange(1, it) },
                    label = "Pitch",
                    size = 38.dp,
                    progressColor = OrpheusColors.synthGreen
                )
                RotaryKnob(
                    value = voiceState.quadGroupHolds[1],
                    onValueChange = { voiceActions.onQuadHoldChange(1, it) },
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
                sequencerFeature = TweakSequencerViewModel.previewFeature(),
                liquidState = liquidState,
                effects = effects,
                onKeyEvent = { _, _ -> false }
            )
        }
    }
}
