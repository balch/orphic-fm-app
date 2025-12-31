package org.balch.orpheus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.balch.orpheus.features.delay.DelayPanelActions
import org.balch.orpheus.features.delay.DelayUiState
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.delay.ModDelayPanelLayout
import org.balch.orpheus.features.distortion.DistortionPanelActions
import org.balch.orpheus.features.distortion.DistortionPanelLayout
import org.balch.orpheus.features.distortion.DistortionUiState
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.evo.AudioEvolutionStrategy
import org.balch.orpheus.features.evo.EvoPanelActions
import org.balch.orpheus.features.evo.EvoPanelLayout
import org.balch.orpheus.features.evo.EvoUiState
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.lfo.HyperLfoPanelLayout
import org.balch.orpheus.features.lfo.LfoPanelActions
import org.balch.orpheus.features.lfo.LfoUiState
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.presets.PresetPanelActions
import org.balch.orpheus.features.presets.PresetUiState
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.stereo.StereoPanelActions
import org.balch.orpheus.features.stereo.StereoPanelLayout
import org.balch.orpheus.features.stereo.StereoUiState
import org.balch.orpheus.features.stereo.StereoViewModel
import org.balch.orpheus.features.tidal.LiveCodePanelActions
import org.balch.orpheus.features.tidal.LiveCodeUiState
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.tidal.ui.LiveCodePanelLayout
import org.balch.orpheus.features.viz.OffViz
import org.balch.orpheus.features.viz.VizPanelLayout
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.panels.compact.CompactAiSection
import org.balch.orpheus.ui.panels.compact.CompactAiSectionPreview
import org.balch.orpheus.ui.panels.compact.CompactPanelSwitcher
import org.balch.orpheus.ui.panels.compact.CompactPanelType
import org.balch.orpheus.ui.panels.compact.CompactPortraitHeaderPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.utils.ViewModelStateActionMapper
import org.balch.orpheus.ui.utils.rememberPanelState
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VizPanelActions
import org.balch.orpheus.ui.viz.VizUiState
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.widgets.DraggableDivider
import org.balch.orpheus.ui.widgets.VizBackground
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter

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
    val voice = rememberPanelState(voiceViewModel)
    val preset = rememberPanelState(presetViewModel)
    val liveCode = rememberPanelState(liveCodeViewModel)
    val delay = rememberPanelState(delayViewModel)
    val distortion = rememberPanelState(distortionViewModel)
    val evo = rememberPanelState(evoViewModel)
    val lfo = rememberPanelState(lfoViewModel)
    val stereo = rememberPanelState(stereoViewModel)
    val viz = rememberPanelState(vizViewModel)

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
        presetFeature = preset,
        voiceFeature = voice,
        distortionFeature = distortion,
        liquidState = liquidState,
        effects = effects,
        liveCodeFeature = liveCode,
        activeReplHighlights = activeHighlights,
        delayFeature = delay,
        evoFeature = evo,
        lfoFeature = lfo,
        stereoFeature = stereo,
        vizFeature = viz
    )
}

@Composable
private fun CompactPortraitScreenLayout(
    modifier: Modifier = Modifier,
    presetFeature: ViewModelStateActionMapper<PresetUiState, PresetPanelActions>,
    voiceFeature: ViewModelStateActionMapper<VoiceUiState, VoicePanelActions>,
    distortionFeature: ViewModelStateActionMapper<DistortionUiState, DistortionPanelActions>,
    liquidState: LiquidState,
    effects: VisualizationLiquidEffects,
    liveCodeFeature: ViewModelStateActionMapper<LiveCodeUiState, LiveCodePanelActions>,
    activeReplHighlights: List<IntRange> = emptyList(),
    delayFeature: ViewModelStateActionMapper<DelayUiState, DelayPanelActions>,
    evoFeature: ViewModelStateActionMapper<EvoUiState, EvoPanelActions>,
    lfoFeature: ViewModelStateActionMapper<LfoUiState, LfoPanelActions>,
    stereoFeature: ViewModelStateActionMapper<StereoUiState, StereoPanelActions>,
    vizFeature: ViewModelStateActionMapper<VizUiState, VizPanelActions>,
    aiSectionContent: @Composable (onShowRepl: () -> Unit) -> Unit = { onShowRepl ->
        CompactAiSection(
            modifier = Modifier.fillMaxSize(),
            onShowRepl = onShowRepl
        )
    }
) {
    // Track section heights
    val density = LocalDensity.current
    var topSectionHeight by remember { mutableStateOf(280.dp) }
    val minTopHeight = 150.dp
    val maxTopHeight = 450.dp

    // Selected panel for switcher
    var selectedPanel by remember { mutableStateOf(CompactPanelType.REPL) }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Header panel
        // 1. Header panel
        CompactPortraitHeaderPanel(
            selectedPresetName = presetFeature.state.selectedPreset?.name ?: "No Preset",
            presets = presetFeature.state.presets,
            presetDropdownExpanded = false, // Simplified - can add state later if needed
            onPresetDropdownExpandedChange = { },
            onPresetSelect = presetFeature.actions.onPresetSelect,
            peakLevel = distortionFeature.state.peak,
            liquidState = liquidState,
            effects = effects
        )

        // 2. Body with visualization background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    Modifier.liquefiable(liquidState)
                )
        ) {
            // Visualization background
            VizBackground(
                modifier = Modifier.fillMaxSize(),
            )

            // Content overlay
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
                            liveCodeFeature = liveCodeFeature,
                            activeReplHighlights = activeReplHighlights,
                            voiceFeature = voiceFeature,
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

                // 3c. AI section (fills remaining space)
                Box(modifier = Modifier.weight(1f)) {
                    aiSectionContent {
                        selectedPanel = CompactPanelType.REPL
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
    liveCodeFeature: ViewModelStateActionMapper<LiveCodeUiState, LiveCodePanelActions>,
    activeReplHighlights: List<IntRange>,
    voiceFeature: ViewModelStateActionMapper<VoiceUiState, VoicePanelActions>,
    delayFeature: ViewModelStateActionMapper<DelayUiState, DelayPanelActions>,
    distortionFeature: ViewModelStateActionMapper<DistortionUiState, DistortionPanelActions>,
    evoFeature: ViewModelStateActionMapper<EvoUiState, EvoPanelActions>,
    lfoFeature: ViewModelStateActionMapper<LfoUiState, LfoPanelActions>,
    stereoFeature: ViewModelStateActionMapper<StereoUiState, StereoPanelActions>,
    vizFeature: ViewModelStateActionMapper<VizUiState, VizPanelActions>,
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
            LiveCodePanelLayout(
                uiState = liveCodeFeature.state,
                actions = liveCodeFeature.actions,
                activeHighlights = activeReplHighlights,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DELAY -> {
            ModDelayPanelLayout(
                uiState = delayFeature.state,
                actions = delayFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.DISTORTION -> {
            DistortionPanelLayout(
                uiState = distortionFeature.state,
                actions = distortionFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }
        
        CompactPanelType.EVO -> {
            EvoPanelLayout(
                uiState = evoFeature.state,
                actions = evoFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.LFO -> {
            HyperLfoPanelLayout(
                uiState = lfoFeature.state,
                actions = lfoFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.STEREO -> {
            StereoPanelLayout(
                uiState = stereoFeature.state,
                actions = stereoFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }

        CompactPanelType.VIZ -> {
            VizPanelLayout(
                uiState = vizFeature.state,
                actions = vizFeature.actions,
                modifier = panelModifier,
                isExpanded = true,
                onExpandedChange = null,
                showCollapsedHeader = false
            )
        }
    }
}

// ==================== PREVIEWS ====================

private object PreviewEvoStrategy : AudioEvolutionStrategy {
    override val id = "preview"
    override val name = "Drift"
    override val color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
    override val knob1Label = "SPEED"
    override val knob2Label = "RANGE"
    override fun setKnob1(value: Float) {}
    override fun setKnob2(value: Float) {}
    override suspend fun evolve(engine: org.balch.orpheus.core.audio.SynthEngine) {}
    override fun onActivate() {}
    override fun onDeactivate() {}
}

@Preview(widthDp = 360, heightDp = 700)
@Composable
private fun CompactPortraitLayoutPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        val liquidState = LocalLiquidState.current
        if (liquidState != null) {
            CompactPortraitScreenLayout(
                presetFeature = ViewModelStateActionMapper(
                    state = PresetUiState(),
                    _actions = PresetPanelActions({}, {}, {}, {}, {}, {})
                ),
                voiceFeature = ViewModelStateActionMapper(
                    state = VoiceUiState(),
                    _actions = VoicePanelActions({}, {})
                ),
                distortionFeature = ViewModelStateActionMapper(
                    state = DistortionUiState(),
                    _actions = DistortionPanelActions({}, {}, {})
                ),
                liquidState = liquidState,
                effects = effects,
                liveCodeFeature = ViewModelStateActionMapper(
                    state = LiveCodeUiState()
                ),
                delayFeature = ViewModelStateActionMapper(
                    state = DelayUiState(),
                ),
                evoFeature = ViewModelStateActionMapper(
                     state = EvoUiState(
                         selectedStrategy = PreviewEvoStrategy,
                         strategies = listOf(PreviewEvoStrategy),
                         isEnabled = false,
                         knob1Value = 0.5f,
                         knob2Value = 0.5f
                     ),
                     _actions = EvoPanelActions({}, {}, {}, {})
                ),
                lfoFeature = ViewModelStateActionMapper(
                    state = LfoUiState(),
                ),
                stereoFeature = ViewModelStateActionMapper(
                    state = StereoUiState(),
                ),
                vizFeature = ViewModelStateActionMapper(
                    state = VizUiState(
                        selectedViz = OffViz(),
                        visualizations = listOf(OffViz()),
                        showKnobs = false
                    ),
                ),
                aiSectionContent = { _ -> CompactAiSectionPreview() }
            )
        }
    }
}
