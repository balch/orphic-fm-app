package org.balch.orpheus.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.sortCompactPanels
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.drum.DrumFeature
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.panels.HeaderFeature
import org.balch.orpheus.ui.panels.HeaderViewModel
import org.balch.orpheus.ui.panels.compact.CompactAiSection
import org.balch.orpheus.ui.panels.compact.CompactBottomPanelSwitcher
import org.balch.orpheus.ui.panels.compact.CompactBottomPanelType
import org.balch.orpheus.ui.panels.compact.CompactPanelSwitcher
import org.balch.orpheus.ui.panels.compact.CompactPortraitHeaderPanel
import org.balch.orpheus.ui.panels.compact.CompactPortraitVoicePads
import org.balch.orpheus.ui.panels.compact.CompactStringPanel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.widgets.DraggableDivider
import org.balch.orpheus.ui.widgets.VizBackground

/**
 * Compact Portrait Layout: Mobile-optimized design for portrait orientation.
 *
 * Layout:
 * - Top: Header panel with title, preset dropdown, volume
 * - Middle: Synth panels (swipeable) - dynamically registered via FeaturePanel system
 * - Divider: Draggable to resize sections
 * - Bottom: AI section with mode selector and chat/dashboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitScreen(
    modifier: Modifier = Modifier,
    headerFeature: HeaderFeature = HeaderViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    drumFeature: DrumFeature = DrumViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    distortionFeature: DistortionFeature = DistortionViewModel.feature(),
) {

    val liquidState = LocalLiquidState.current ?: rememberLiquidState()
    val effects = LocalLiquidEffects.current

    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Track section heights
    val density = LocalDensity.current
    var topSectionHeight by remember { mutableStateOf(280.dp) }
    val minTopHeight = 150.dp
    val maxTopHeight = 450.dp

    // Derive compact panels from registered FeaturePanels
    val compactPanels = remember(headerFeature.sortedPanels) {
        sortCompactPanels(headerFeature.sortedPanels)
    }
    var selectedPanelId by remember { mutableStateOf(PanelId.EVO) }

    // Selected panel for bottom panel switcher
    var selectedBottomPanel by remember { mutableStateOf(CompactBottomPanelType.PADS) }

    val vizState by vizFeature.stateFlow.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    voiceFeature = voiceFeature,
                    drumFeature = drumFeature,
                    isDialogActive = false
                )
            }
            .then(
                Modifier.liquefiable(liquidState)
            )
    ) {
        // 0. Visualization background (behind everything, including status bar)
        VizBackground(
            modifier = Modifier.fillMaxSize(),
            selectedViz = vizState.selectedViz,
        )

        // Content Overlay
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Header panel
            CompactPortraitHeaderPanel(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp),
                distortionFeature = distortionFeature,
                liquidState = liquidState,
                effects = effects
            )

            // 2. Main Content
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                     // 3a. Synth panels section (resizable)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(topSectionHeight)
                    ) {
                        CompactPanelSwitcher(
                            panels = compactPanels,
                            selectedPanelId = selectedPanelId,
                            onPanelSelected = { selectedPanelId = it },
                        )
                    }

                    // 3b. Draggable divider
                    DraggableDivider(
                        onDrag = { delta ->
                            with(density) {
                                val newHeight = topSectionHeight + delta.toDp()
                                topSectionHeight = newHeight.coerceIn(minTopHeight, maxTopHeight)
                            }
                        }
                    )

                    // 3c. Bottom panel section (fills remaining space)
                    Box(modifier = Modifier.weight(1f)) {
                        CompactBottomPanelSwitcher(
                            selectedPanel = selectedBottomPanel,
                            onPanelSelected = { selectedBottomPanel = it },
                            modifier = Modifier.fillMaxSize()
                        ) { bottomPanel ->
                            when (bottomPanel) {
                                CompactBottomPanelType.AI -> {
                                    CompactAiSection(
                                        modifier = Modifier.fillMaxSize(),
                                        onShowRepl = { selectedPanelId = PanelId.CODE }
                                    )
                                }
                                CompactBottomPanelType.PADS -> {
                                    val voiceState by voiceFeature.stateFlow.collectAsState()
                                    CompactPortraitVoicePads(
                                        voiceState = voiceState,
                                        actions = voiceFeature.actions,
                                        modifier = Modifier.fillMaxSize(),
                                        liquidState = liquidState,
                                        effects = effects
                                    )
                                }
                                CompactBottomPanelType.STRINGS -> {
                                    val voiceState by voiceFeature.stateFlow.collectAsState()
                                    CompactStringPanel(
                                        voiceState = voiceState,
                                        actions = voiceFeature.actions,
                                        modifier = Modifier.fillMaxSize(),
                                        liquidState = liquidState,
                                        effects = effects
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 700)
@Composable
private fun CompactPortraitLayoutPreview() {
    LiquidPreviewContainerWithGradient() {
        CompactPortraitScreen(
            headerFeature = HeaderViewModel.previewFeature(),
            voiceFeature = VoiceViewModel.previewFeature(),
            drumFeature = DrumViewModel.previewFeature(),
            vizFeature = VizViewModel.previewFeature(),
            distortionFeature = DistortionViewModel.previewFeature(),
        )
    }
}
