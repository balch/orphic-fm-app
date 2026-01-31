package org.balch.orpheus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.features.beats.DrumBeatsFeature
import org.balch.orpheus.features.beats.DrumBeatsPanel
import org.balch.orpheus.features.beats.DrumBeatsViewModel
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.DelayFeedbackPanel
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.drum.DrumFeature
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.drum.DrumsPanel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoPanel
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.flux.FluxFeature
import org.balch.orpheus.features.flux.FluxPanel
import org.balch.orpheus.features.flux.FluxViewModel
import org.balch.orpheus.features.grains.GrainsFeature
import org.balch.orpheus.features.grains.GrainsPanel
import org.balch.orpheus.features.grains.GrainsViewModel
import org.balch.orpheus.features.lfo.DuoLfoPanel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.resonator.ResonatorFeature
import org.balch.orpheus.features.resonator.ResonatorPanel
import org.balch.orpheus.features.resonator.ResonatorViewModel
import org.balch.orpheus.features.tidal.LiveCodeFeature
import org.balch.orpheus.features.tidal.LiveCodePanel
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tweaks.ModTweaksPanel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizPanel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.features.warps.WarpsFeature
import org.balch.orpheus.features.warps.WarpsPanel
import org.balch.orpheus.features.warps.WarpsViewModel
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
import org.balch.orpheus.ui.widgets.DraggableDivider
import org.balch.orpheus.ui.widgets.VizBackground

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
    liveCodeFeature: LiveCodeFeature = LiveCodeViewModel.feature(),
    presetFeature: PresetsFeature = PresetsViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    distortionFeature: DistortionFeature = DistortionViewModel.feature(),
    delayFeature: DelayFeature = DelayViewModel.feature(),
    evoFeature: EvoFeature = EvoViewModel.feature(),
    lfoFeature: LfoFeature = LfoViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    drumFeature: DrumFeature = DrumViewModel.feature(),
    grainsFeature: GrainsFeature = GrainsViewModel.feature(),
    drumBeatsFeature: DrumBeatsFeature = DrumBeatsViewModel.feature(),
    resonatorFeature: ResonatorFeature = ResonatorViewModel.feature(),
    warpsFeature: WarpsFeature = WarpsViewModel.feature(),
    fluxFeature: FluxFeature = FluxViewModel.feature(),
) {

    // Track active highlight ranges for token highlighting (Map of unique ID to range)
    var activeHighlightMap by remember { mutableStateOf(mapOf<Long, IntRange>()) }
    var highlightIdCounter by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    // Subscribe to trigger events for token highlighting
    LaunchedEffect(liveCodeFeature) {
        liveCodeFeature.triggers.collect { triggerEvent ->
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

    // Request focus for keyboard input
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
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
                                voiceFeature = voiceFeature,
                                liveCodeFeature = liveCodeFeature,
                                delayFeature = delayFeature,
                                distortionFeature = distortionFeature,
                                evoFeature = evoFeature,
                                lfoFeature = lfoFeature,
                                vizFeature = vizFeature,
                                drumFeature = drumFeature,
                                drumBeatsFeature = drumBeatsFeature,
                                resonatorFeature = resonatorFeature,
                                grainsFeature = grainsFeature,
                                warpsFeature = warpsFeature,
                                fluxFeature = fluxFeature,
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
    voiceFeature: VoicesFeature,
    liveCodeFeature: LiveCodeFeature,
    delayFeature: DelayFeature,
    distortionFeature: DistortionFeature,
    evoFeature: EvoFeature,
    lfoFeature: LfoFeature,
    vizFeature: VizFeature,
    drumFeature: DrumFeature,
    drumBeatsFeature: DrumBeatsFeature,
    resonatorFeature: ResonatorFeature,
    grainsFeature: GrainsFeature,
    warpsFeature: WarpsFeature,
    fluxFeature: FluxFeature,
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
            LiveCodePanel(
                feature = liveCodeFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false,
            )
        }

        CompactPanelType.PRESET -> {
            PresetsPanel(
                feature = presetFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DELAY -> {
            DelayFeedbackPanel(
                feature = delayFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DISTORTION -> {
            DistortionPanel(
                feature = distortionFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }
        
        CompactPanelType.EVO -> {
            EvoPanel(
                evoFeature = evoFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.TWEAKS -> {
            ModTweaksPanel(
                voiceFeature = voiceFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.LFO -> {
            DuoLfoPanel(
                feature = lfoFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.VIZ -> {
            VizPanel(
                feature = vizFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DRUMS -> {
            DrumsPanel(
                drumFeature = drumFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false,
            )
        }

        CompactPanelType.PATTERN -> {
            DrumBeatsPanel(
                drumBeatsFeature = drumBeatsFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false,
            )
        }
        CompactPanelType.RESONATOR -> {
            ResonatorPanel(
                feature = resonatorFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.GRAINS-> {
            GrainsPanel(
                feature = grainsFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }
        
        CompactPanelType.WARPS -> {
            WarpsPanel(
                feature = warpsFeature,
                modifier = panelModifier,
                isExpanded = true,
                showCollapsedHeader = false
            )
        }
        
        CompactPanelType.FLUX -> {
            FluxPanel(
                flux = fluxFeature,
                modifier = panelModifier,
                isExpanded = true,
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
        CompactPortraitScreen(
            presetFeature = PresetsViewModel.previewFeature(),
            voiceFeature = VoiceViewModel.previewFeature(),
            distortionFeature = DistortionViewModel.previewFeature(),
            liveCodeFeature = LiveCodeViewModel.previewFeature(),
            delayFeature = DelayViewModel.previewFeature(),
            evoFeature = EvoViewModel.previewFeature(),
            lfoFeature = LfoViewModel.previewFeature(),
            vizFeature = VizViewModel.previewFeature(),
            grainsFeature = GrainsViewModel.previewFeature(),
            drumFeature = DrumViewModel.previewFeature(),
            drumBeatsFeature = DrumBeatsViewModel.previewFeature(),
            resonatorFeature = ResonatorViewModel.previewFeature(),
            warpsFeature = WarpsViewModel.previewFeature(),
            fluxFeature = FluxViewModel.previewFeature(),
        )
    }
}

@Preview
@Composable
private fun PanelContentPreview() {
    LiquidPreviewContainerWithGradient {
        PanelContent(
            panel = CompactPanelType.EVO,
            presetFeature = PresetsViewModel.previewFeature(),
            voiceFeature = VoiceViewModel.previewFeature(),
            liveCodeFeature = LiveCodeViewModel.previewFeature(),
            delayFeature = DelayViewModel.previewFeature(),
            distortionFeature = DistortionViewModel.previewFeature(),
            evoFeature = EvoViewModel.previewFeature(),
            lfoFeature = LfoViewModel.previewFeature(),
            vizFeature = VizViewModel.previewFeature(),
            drumFeature = DrumViewModel.previewFeature(),
            drumBeatsFeature = DrumBeatsViewModel.previewFeature(),
            resonatorFeature = ResonatorViewModel.previewFeature(),
            grainsFeature = GrainsViewModel.previewFeature(),
            warpsFeature = WarpsViewModel.previewFeature(),
            fluxFeature = FluxViewModel.previewFeature(),
        )
    }
}
