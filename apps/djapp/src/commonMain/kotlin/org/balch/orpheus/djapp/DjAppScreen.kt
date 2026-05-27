package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.horn.HornPanel
import org.balch.orpheus.features.horn.HornViewModel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.mixer.MixerPanel
import org.balch.orpheus.features.pulsar.mixer.MixerViewModel
import org.balch.orpheus.features.reverb.ReverbPanel
import org.balch.orpheus.features.reverb.ReverbViewModel
import org.balch.orpheus.features.timer.TimerPanel
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.darken
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun DjAppScreen(
    synthEngine: SynthEngine,
    vizFeature: VizFeature,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val djFeature = DjViewModel.feature()
    val pulsarFeature = PulsarViewModel.feature()
    // Eagerly create all feature VMs so their port values sync to C++ at startup,
    // not lazily when the user first navigates to their tab.
    val reverbFeature = ReverbViewModel.feature()
    val distortionFeature = DistortionViewModel.feature()
    val hornFeature = HornViewModel.feature()
    val timerFeature = TimerViewModel.feature()
    val mixerFeature = MixerViewModel.feature()

    // Nav3 back stack — single-level tab switching
    val backStack = remember { NavBackStack<DjRoute>(DjTab) }
    val currentRoute = backStack.lastOrNull() ?: DjTab

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val isLandscape = maxWidth > maxHeight

        DjAppNavScaffold(
            currentRoute = currentRoute,
            onRouteSelected = { route ->
                if (route != currentRoute) {
                    backStack.clear()
                    backStack.add(route)
                }
            },
            isLandscape = isLandscape,
            pulsarFeature = pulsarFeature,
            timerFeature = timerFeature,
            onTogglePlayback = onTogglePlayback,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Shared nav content composable used in both orientations
            val navContent: @Composable (Modifier) -> Unit = { navModifier ->
                Box(modifier = navModifier) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<DjTab> {
                                DjPanel(
                                    feature = djFeature,
                                    vizFlowA = synthEngine.djVizFlowA,
                                    vizFlowB = synthEngine.djVizFlowB,
                                    outVizFlow = synthEngine.djOutVizFlow,
                                    beatPhaseFlow = synthEngine.beatPhaseFlow,
                                    modifier = Modifier.fillMaxSize(),
                                    isExpanded = true,
                                    onExpandedChange = {},
                                    showCollapsedHeader = false,
                                    showExpandedTitle = false,
                                )
                            }
                            entry<TimerTab> {
                                TimerPanel(
                                    modifier = Modifier.fillMaxSize(),
                                    showCollapsedHeader = false,
                                    showExpandedTitle = false,
                                )
                            }
                            entry<MixTab> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    ReverbPanel(
                                        inVizFlow = synthEngine.reverbInVizFlow,
                                        outVizFlow = synthEngine.reverbOutVizFlow,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        showExpandedTitle = false,
                                    )
                                    MixerPanel(
                                        feature = mixerFeature,
                                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                                        masterOutVizFlow = synthEngine.masterOutVizFlow,
                                        modifier = Modifier.fillMaxWidth(),
                                        isExpanded = true,
                                        onExpandedChange = {},
                                        showCollapsedHeader = false,
                                        showExpandedTitle = false,
                                    )
                                }
                            }
                            entry<HornTab> {
                                HornPanel(
                                    inVizFlow = synthEngine.hornInVizFlow,
                                    outVizFlow = synthEngine.hornOutVizFlow,
                                    hornPhaseVizFlow = synthEngine.hornPhaseVizFlow,
                                    wooferPhaseVizFlow = synthEngine.wooferPhaseVizFlow,
                                    modifier = Modifier.fillMaxSize(),
                                    isExpanded = true,
                                    onExpandedChange = {},
                                    showCollapsedHeader = false,
                                    showExpandedTitle = false,
                                )
                            }
                        },
                    )
                }
            }

            if (isLandscape) {
                // Landscape: Header top, Pulsar left + nav content right
                Row(modifier = Modifier.fillMaxWidth()) {
                    PulsarPanel(
                        modifier = Modifier.weight(.5f).fillMaxHeight(),
                        pulsar = pulsarFeature,
                        vizFlow = synthEngine.pulsarVizFlow,
                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                        isExpanded = true,
                        onExpandedChange = {},
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                    )
                    Column(
                        modifier = Modifier
                            .weight(.5f)
                            .fillMaxHeight()
                            .padding(top = 4.dp)) {
                        DjAppHeaderRow(
                            vizFeature = vizFeature,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalPadding = 0.dp,
                        )
                        navContent(Modifier)
                    }
                }
            } else {
                // Portrait: Header, Pulsar top, nav content bottom
                Column(modifier = Modifier.fillMaxSize()) {
                    DjAppHeaderRow(
                        vizFeature = vizFeature,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    PulsarPanel(
                        pulsar = pulsarFeature,
                        vizFlow = synthEngine.pulsarVizFlow,
                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                        modifier = Modifier.weight(.6f).fillMaxWidth(),
                        isExpanded = true,
                        onExpandedChange = {},
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                    )
                    navContent(Modifier.weight(.4f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DjAppHeaderRow(
    vizFeature: VizFeature,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
) {
    val effects = LocalLiquidEffects.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTitleTreatment(
            title = "Orphic DJ",
            modifier = Modifier.height(36.dp),
            effects = effects,
            showSizeEffects = false,
            horizontalPadding = horizontalPadding,
        )

        VizDropdown(vizFeature = vizFeature)
    }
}

@Composable
private fun VizDropdown(vizFeature: VizFeature) {
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val fullState by vizFeature.stateFlow.collectAsState()
    val vizName by remember { derivedStateOf { fullState.selectedViz.name } }
    val isRandom by remember { derivedStateOf { fullState.isRandomVizMode } }
    val visualizations by remember { derivedStateOf { fullState.visualizations } }
    val vizActions = vizFeature.actions
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (liquidState != null) {
                        Modifier.liquidVizEffects(
                            liquidState = liquidState,
                            scope = effects.top,
                            frostAmount = 8.dp,
                            color = OrpheusColors.panelSurface.darken(),
                            shape = RoundedCornerShape(8.dp),
                        )
                    } else {
                        Modifier.background(OrpheusColors.panelSurface)
                    }
                )
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Viz: " + when {
                        isRandom -> "Random"
                        else -> vizName
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = effects.title.titleColor.lighten(),
                    maxLines = 1
                )
                Text(
                    text = if (expanded) " ▲" else " ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = effects.title.titleColor.lighten(),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(OrpheusColors.panelSurface),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Random",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRandom) OrpheusColors.neonCyan else Color.White,
                    )
                },
                onClick = {
                    vizActions.onSetRandomMode(true)
                    expanded = false
                },
            )
            visualizations.forEach { viz ->
                DropdownMenuItem(
                    text = {
                        Text(
                            viz.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    },
                    onClick = {
                        vizActions.onSelectViz(viz)
                        expanded = false
                    },
                )
            }
        }
    }
}


// ==================== PREVIEWS ====================

private val emptyVizFlow = MutableStateFlow(FloatArray(0))
private val emptyPulsarVizFlow = MutableStateFlow(PulsarVizData())
private val emptyTrackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) }

/**
 * Previewable layout — portrait (Pulsar top, content bottom) or
 * landscape (Pulsar left, content right).
 */
@Composable
private fun DjAppPreviewLayout(
    selectedTab: DjRoute = DjTab,
    landscape: Boolean = false,
    pulsarFeature: PulsarFeature = PulsarViewModel.previewFeature(),
    modifier: Modifier = Modifier,
    tabContent: @Composable (Modifier) -> Unit,
) {
    if (landscape) {
        Row(modifier = modifier.fillMaxSize()) {
            PulsarPanel(
                pulsar = pulsarFeature,
                vizFlow = emptyPulsarVizFlow,
                trackVizFlows = emptyTrackVizFlows,
                modifier = Modifier.weight(.5f).fillMaxHeight(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
            tabContent(Modifier.weight(.5f).fillMaxHeight())
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            DjAppHeaderRow(
                vizFeature = VizViewModel.previewFeature(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            PulsarPanel(
                pulsar = pulsarFeature,
                vizFlow = emptyPulsarVizFlow,
                trackVizFlows = emptyTrackVizFlows,
                modifier = Modifier.weight(.6f).fillMaxWidth(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
            tabContent(Modifier.weight(.4f).fillMaxWidth())
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "DJ Tab")
@Composable
private fun DjTabPreview() {
    DjAppPreviewLayout(selectedTab = DjTab) { mod ->
        DjPanel(
            feature = DjViewModel.previewFeature(),
            vizFlowA = emptyVizFlow,
            vizFlowB = emptyVizFlow,
            outVizFlow = emptyVizFlow,
            modifier = mod,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
        )
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "Mix Tab")
@Composable
private fun MixTabPreview() {
    DjAppPreviewLayout(selectedTab = MixTab) { mod ->
        Column(modifier = mod) {
            ReverbPanel(
                feature = ReverbViewModel.previewFeature(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
            DistortionPanel(
                feature = DistortionViewModel.previewFeature(),
                modifier = Modifier.fillMaxWidth(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "Horn Tab")
@Composable
private fun HornTabPreview() {
    DjAppPreviewLayout(selectedTab = HornTab) { mod ->
        HornPanel(
            feature = HornViewModel.previewFeature(),
            modifier = mod,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
        )
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "Timer Tab")
@Composable
private fun TimerTabPreview() {
    DjAppPreviewLayout(selectedTab = TimerTab) { mod ->
        TimerPanel(
            feature = TimerViewModel.previewFeature(),
            modifier = mod,
            showCollapsedHeader = false,
            showExpandedTitle = false,
        )
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Running")
@Composable
private fun DjAppNavTimerRunningPreview() {
    val runningTimer = TimerViewModel.previewFeature(
        TimerUiState(
            initialTime = 45.minutes,
            remainingTime = 42.minutes.plus(13.seconds),
            status = TimerStatus.RUNNING,
        ),
    )
    DjAppNavScaffold(
        currentRoute = DjTab,
        onRouteSelected = {},
        isLandscape = false,
        pulsarFeature = PulsarViewModel.previewFeature(),
        timerFeature = runningTimer,
        onTogglePlayback = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("(preview)", color = Color.White)
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Paused")
@Composable
private fun DjAppNavTimerPausedPreview() {
    val pausedTimer = TimerViewModel.previewFeature(
        TimerUiState(
            initialTime = 45.minutes,
            remainingTime = 12.minutes,
            status = TimerStatus.PAUSED,
        ),
    )
    DjAppNavScaffold(
        currentRoute = DjTab,
        onRouteSelected = {},
        isLandscape = false,
        pulsarFeature = PulsarViewModel.previewFeature(),
        timerFeature = pausedTimer,
        onTogglePlayback = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("(preview)", color = Color.White)
        }
    }
}

// ── Landscape Previews ──

@Preview(widthDp = 780, heightDp = 360, name = "DJ Tab — Landscape")
@Composable
private fun DjTabLandscapePreview() {
    DjAppPreviewLayout(selectedTab = DjTab, landscape = true) { mod ->
        DjPanel(
            feature = DjViewModel.previewFeature(),
            vizFlowA = emptyVizFlow,
            vizFlowB = emptyVizFlow,
            outVizFlow = emptyVizFlow,
            modifier = mod,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
        )
    }
}

@Preview(widthDp = 780, heightDp = 360, name = "Mix Tab — Landscape")
@Composable
private fun MixTabLandscapePreview() {
    DjAppPreviewLayout(selectedTab = MixTab, landscape = true) { mod ->
        Column(modifier = mod) {
            ReverbPanel(
                feature = ReverbViewModel.previewFeature(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
            DistortionPanel(
                feature = DistortionViewModel.previewFeature(),
                modifier = Modifier.fillMaxWidth(),
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
        }
    }
}

@Preview(widthDp = 780, heightDp = 360, name = "Horn Tab — Landscape")
@Composable
private fun HornTabLandscapePreview() {
    DjAppPreviewLayout(selectedTab = HornTab, landscape = true) { mod ->
        HornPanel(
            feature = HornViewModel.previewFeature(),
            modifier = mod,
            isExpanded = true,
            onExpandedChange = {},
            showCollapsedHeader = false,
            showExpandedTitle = false,
        )
    }
}
