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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.djapp.variant.mergeTabContributions
import org.balch.orpheus.djapp.vibeinfo.VibeInfoSheet
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
    tabContributions: List<DjTabContribution> = emptyList(),
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
    val tabs = remember(tabContributions) { mergeTabContributions(djTabs, tabContributions) }

    // Single source of truth for which overlay sheet (if any) is open: a tab-sheet contribution's
    // route (e.g. AiTab), the title-triggered VibeInfoTab, or null. One state can only hold one
    // route, so opening a second sheet naturally replaces the first — no manual "clear the other"
    // bookkeeping. rememberSaveable so an Android config change (rotation recreates the activity)
    // keeps the sheet open and the app-lifetime AI ViewModel observed, instead of closing the sheet
    // mid-generation while the agent keeps running unseen.
    val sheetRouteSaver = remember(tabs) {
        val byKey = (tabs + VibeInfoTab).associateBy { it.label }
        Saver<DjRoute?, String>(
            save = { route -> route?.label ?: "" },
            restore = { key -> byKey[key] },
        )
    }
    var activeSheet by rememberSaveable(stateSaver = sheetRouteSaver) {
        mutableStateOf<DjRoute?>(null)
    }

    BoxWithConstraints(
        // Fully edge-to-edge: no inset padding, so the UI (and the VizBackground
        // behind it) fill into the display cutout instead of letterboxing below the
        // notch. System bars are hidden in MainActivity; the header Row is
        // SpaceBetween, so on a center punch-hole the title and Viz dropdown sit to
        // either side of the hole.
        modifier = modifier
            .fillMaxSize(),
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
            tabs = tabs,
            onOpenSheet = { route ->
                // Toggle: tapping the active sheet's nav item closes it, otherwise open (replacing
                // whatever sheet was open). Uses the tapped route rather than a hardcoded AiTab.
                activeSheet = if (activeSheet == route) null else route
            },
            openSheetRoute = activeSheet,
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
                            onInfoClick = { activeSheet = VibeInfoTab },
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
                        onInfoClick = { activeSheet = VibeInfoTab },
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

            // VibeInfo is title-triggered, not a tab contribution, so it keeps its dedicated
            // composable — but shares the single activeSheet state.
            if (activeSheet == VibeInfoTab) {
                VibeInfoSheet(
                    pulsar = pulsarFeature,
                    vizFlow = synthEngine.pulsarVizFlow,
                    onDismiss = { activeSheet = null },
                )
            }

            // Generic tab-sheet rendering: any contribution whose route opens as a sheet stays
            // composed the whole time and is told whether it is the active sheet via isOpen (it
            // renders its own chrome only while open). Keeping it composed while closed lets the
            // contribution cancel in-flight work when isOpen goes true->false — covering EVERY close
            // path (nav-item toggle, switching to another sheet, scrim/drag/back), not just the
            // scrim. A second sheet contribution would work here with no new code.
            tabContributions.forEach { contribution ->
                if (contribution.route.opensAsSheet) {
                    contribution.Content(
                        isOpen = activeSheet == contribution.route,
                        modifier = Modifier.fillMaxSize(),
                        isLandscape = isLandscape,
                        onDismiss = { activeSheet = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun DjAppHeaderRow(
    vizFeature: VizFeature,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
) {
    val effects = LocalLiquidEffects.current

    Row(
        // iOS: push the header below the Dynamic Island / notch (the status bar is hidden, but the
        // Island still reserves top safe area). Resolves to zero on Android/desktop, so the shipped
        // edge-to-edge layout there is unchanged. The VizBackground behind this still fills into the
        // cutout — only this foreground chrome is inset.
        modifier = modifier
            .windowInsetsPadding(platformSafeAreaInsets().only(WindowInsetsSides.Top))
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Title + Info button grouped on the left, inset-padded to stay clear of
        // side camera notches in landscape (edge-to-edge layout: no outer inset padding).
        Row(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title itself is the Vibe Info trigger (raised, tappable) — no separate Info button.
            AppTitleTreatment(
                title = "Orphic DJ",
                modifier = Modifier.height(32.dp),
                effects = effects,
                showSizeEffects = false,
                horizontalPadding = horizontalPadding,
                verticalPadding = 4.dp,
                onClick = onInfoClick,
            )
        }

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
                onInfoClick = {},
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
