package org.balch.orpheus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.delay.ModDelayPanel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoPanel
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.lfo.HyperLfoPanel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.stereo.StereoFeature
import org.balch.orpheus.features.stereo.StereoPanel
import org.balch.orpheus.features.stereo.StereoViewModel
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tidal.ui.LiveCodeFeature
import org.balch.orpheus.features.tidal.ui.LiveCodePanelLayout
import org.balch.orpheus.features.viz.VizFeature
import org.balch.orpheus.features.viz.VizPanel
import org.balch.orpheus.features.viz.VizViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.panels.compact.CompactAiSection
import org.balch.orpheus.ui.panels.compact.CompactBottomPanelSwitcher
import org.balch.orpheus.ui.panels.compact.CompactBottomPanelType
import org.balch.orpheus.ui.panels.compact.CompactPanelSwitcher
import org.balch.orpheus.ui.panels.compact.CompactPanelType
import org.balch.orpheus.ui.panels.compact.CompactPortraitHeaderPanel
import org.balch.orpheus.ui.panels.compact.CompactPortraitVoicePads
import org.balch.orpheus.ui.panels.compact.CompactStringPanel
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.widgets.DraggableDivider
import org.balch.orpheus.ui.widgets.VizBackground
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compact Portrait Layout: Mobile-optimized design for portrait orientation.
 *
 * Layout:
 * - Top: Header panel with title, preset dropdown, volume
 * - Middle: Synth panels (swipeable) - REPL, Global, Delay, Distortion, LFO, Stereo, Viz
 * - Divider: Draggable to resize sections
 * - Bottom: AI section with mode selector and chat/dashboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitScreen(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
    vizViewModel: VizViewModel = metroViewModel(),
    delayViewModel: DelayViewModel = metroViewModel(),
    distortionViewModel: DistortionViewModel = metroViewModel(),

    evoViewModel: EvoViewModel = metroViewModel(),
    lfoViewModel: LfoViewModel = metroViewModel(),
    stereoViewModel: StereoViewModel = metroViewModel(),
    liveCodeViewModel: LiveCodeViewModel = metroViewModel(),
) {


    // Track active highlight ranges for token highlighting (Map of unique ID to range)
    var activeHighlightMap by remember { mutableStateOf(mapOf<Long, IntRange>()) }
    var highlightIdCounter by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    // Derive active highlights from the map for the transformer
    val activeHighlights = activeHighlightMap.values.toList()

    // Subscribe to trigger events for token highlighting
    LaunchedEffect(liveCodeViewModel) {
        liveCodeViewModel.triggers.collect { triggerEvent ->
            // Create unique IDs for each new highlight
            val newHighlights = triggerEvent.locations.associate { loc ->
                val id = highlightIdCounter++
                id to (loc.start until loc.end)
            }
            activeHighlightMap = activeHighlightMap + newHighlights

            // Launch coroutine to clear these specific highlights after duration
            val idsToRemove = newHighlights.keys
            scope.launch {
                delay(triggerEvent.durationMs)
                activeHighlightMap = activeHighlightMap - idsToRemove
            }
        }
    }

    val liquidState = LocalLiquidState.current ?: rememberLiquidState()
    val effects = LocalLiquidEffects.current

    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    CompactPortraitScreenLayout(
        modifier = modifier,
        presetFeature = presetViewModel,
        voiceFeature = voiceViewModel,
        distortionFeature = distortionViewModel,
        liquidState = liquidState,
        effects = effects,
        liveCodeFeature = liveCodeViewModel,
        activeReplHighlights = activeHighlights,
        delayFeature = delayViewModel,
        evoFeature = evoViewModel,
        lfoFeature = lfoViewModel,
        stereoFeature = stereoViewModel,
        vizFeature = vizViewModel
    )
}

@Composable
private fun CompactPortraitScreenLayout(
    modifier: Modifier = Modifier,
    presetFeature: PresetsFeature,
    voiceFeature: VoicesFeature,
    distortionFeature: DistortionFeature,
    liquidState: LiquidState,
    effects: VisualizationLiquidEffects,
    liveCodeFeature: LiveCodeFeature,
    activeReplHighlights: List<IntRange> = emptyList(),
    delayFeature: DelayFeature,
    evoFeature: EvoFeature,
    lfoFeature: LfoFeature,
    stereoFeature: StereoFeature,
    vizFeature: VizFeature,
) {
    // Track section heights
    val density = LocalDensity.current
    var topSectionHeight by remember { mutableStateOf(280.dp) }
    val minTopHeight = 150.dp
    val maxTopHeight = 450.dp

    // Selected panel for top switcher
    var selectedPanel by remember { mutableStateOf(CompactPanelType.EVO) }
    
    // Selected panel for bottom panel switcher
    var selectedBottomPanel by remember { mutableStateOf(CompactBottomPanelType.PADS) }

    val vizState by vizFeature.stateFlow.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                Modifier.liquefiable(liquidState)
            )
    ) {
        // 0. Visualization background (behind everything, including status bar)
        VizBackground(
            modifier = Modifier.fillMaxSize(),
            selectedViz = vizState.selectedViz,
        )

        // Content Overylay
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
                            selectedPanel = selectedPanel,
                            onPanelSelected = { selectedPanel = it }
                        ) { panel ->
                            PanelContent(
                                panel = panel,
                                presetFeature = presetFeature,
                                liveCodeFeature = liveCodeFeature,
                                activeReplHighlights = activeReplHighlights,
                                delayFeature = delayFeature,
                                distortionFeature = distortionFeature,
                                evoFeature = evoFeature,
                                lfoFeature = lfoFeature,
                                stereoFeature = stereoFeature,
                                vizFeature = vizFeature,
                            )
                        }
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
                                        onShowRepl = { selectedPanel = CompactPanelType.REPL }
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

/**
 * Renders the appropriate panel content based on selection.
 */
@Composable
private fun PanelContent(
    panel: CompactPanelType,
    presetFeature: PresetsFeature,
    liveCodeFeature: LiveCodeFeature,
    activeReplHighlights: List<IntRange>,
    delayFeature: DelayFeature,
    distortionFeature: DistortionFeature,
    evoFeature: EvoFeature,
    lfoFeature: LfoFeature,
    stereoFeature: StereoFeature,
    vizFeature: VizFeature,
    modifier: Modifier = Modifier,
) {
    val panelModifier = modifier
        .fillMaxSize()
        .background(
            OrpheusColors.darkVoid.copy(alpha = 0.6f),
            RoundedCornerShape(12.dp)
        )

    when (panel) {
        CompactPanelType.REPL -> {
            val state by liveCodeFeature.stateFlow.collectAsState()
            LiveCodePanelLayout(
                uiState = state,
                actions = liveCodeFeature.actions,
                activeHighlights = activeReplHighlights,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.PRESET -> {
            PresetsPanel(
                feature = presetFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DELAY -> {
            ModDelayPanel(
                feature = delayFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DISTORTION -> {
            DistortionPanel(
                feature = distortionFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }
        
        CompactPanelType.EVO -> {
            EvoPanel(
                evoFeature = evoFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.LFO -> {
            HyperLfoPanel(
                feature = lfoFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.STEREO -> {
            StereoPanel(
                feature = stereoFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.VIZ -> {
            VizPanel(
                feature = vizFeature,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 700)
@Composable
private fun CompactPortraitLayoutPreview() {
    LiquidPreviewContainerWithGradient() {
        val liquidState = LocalLiquidState.current
        if (liquidState != null) {
            CompactPortraitScreenLayout(
                presetFeature = PresetsViewModel.previewFeature(),
                voiceFeature = VoiceViewModel.previewFeature(),
                distortionFeature = DistortionViewModel.previewFeature(),
                liquidState = liquidState,
                effects = VisualizationLiquidEffects.Default,
                liveCodeFeature = LiveCodeViewModel.previewFeature(),
                delayFeature = DelayViewModel.previewFeature(),
                evoFeature = EvoViewModel.previewFeature(),
                lfoFeature = LfoViewModel.previewFeature(),
                stereoFeature = StereoViewModel.previewFeature(),
                vizFeature = VizViewModel.previewFeature()
            )
        }
    }
}
