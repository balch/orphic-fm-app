package org.balch.orpheus.djapp

import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.djapp.variant.mergeTabContributions
import org.balch.orpheus.djapp.vibeinfo.VibeInfoPanel
import org.balch.orpheus.djapp.vibeinfo.VibeInfoSheet
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.horn.HornPanel
import org.balch.orpheus.features.horn.HornViewModel
import org.balch.orpheus.features.pulsar.EndsPanel
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
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.TvFocusFadeOutMs
import org.balch.orpheus.ui.infrastructure.TvFocusIdleTimeoutMs
import org.balch.orpheus.ui.infrastructure.TvFocusRegionHolder
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.darken
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.readableOnDark
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun DjAppScreen(
    synthEngine: SynthEngine,
    vizFeature: VizFeature,
    appPreferencesRepository: AppPreferencesRepository,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    tabContributions: List<DjTabContribution> = emptyList(),
) {
    val djFeature = DjViewModel.feature()
    val pulsarFeature = PulsarViewModel.feature()
    val timerFeature = TimerViewModel.feature()
    val mixerFeature = MixerViewModel.feature()
    val scope = rememberCoroutineScope()

    // Nav3 back stack — single-level tab switching
    val backStack = remember { NavBackStack<DjRoute>(DjTab) }
    val currentRoute = backStack.lastOrNull() ?: DjTab
    val tabs = remember(tabContributions) { mergeTabContributions(djTabs, tabContributions) }

    // Single state for "which sheet is open" (a tab-sheet contribution's route, VibeInfoTab, or
    // null) — one value replacing itself needs no separate "close the other sheet" bookkeeping.
    // rememberSaveable: survives Android rotation so an in-flight AI generation stays visible
    // instead of the sheet closing mid-run while the agent keeps working unseen.
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

    // An ordered list, not a single route: TV docks several panels at once, and toggle order is
    // slot-fill order (see assignDock). Null until prefs load so the first frame doesn't flash
    // the default set before the restored one replaces it.
    var dockedPanels by remember { mutableStateOf<List<DjRoute>?>(null) }
    val dockablePanels = remember(tabs) { largeScreenPanels(tabs) }

    val toggleDocked: (DjRoute) -> Unit = { route ->
        val current = dockedPanels.orEmpty()
        // Appending on enable is what makes toggle order the slot order.
        val next = if (route in current) current - route else current + route
        dockedPanels = next
        scope.launch {
            appPreferencesRepository.update {
                it.copy(largeScreenPanels = next.map(DjRoute::label))
            }
        }
    }

    LaunchedEffect(dockablePanels) {
        val byLabel = dockablePanels.associateBy { it.label }
        val saved = appPreferencesRepository.load().largeScreenPanels
        // A toggle during this suspend already set a concrete value and persisted it; applying
        // the load's result now would only revert the on-screen dock, since the toggle's write
        // still stands on disk. Only seed from prefs if nothing has claimed dockedPanels yet.
        if (dockedPanels == null) {
            dockedPanels = saved?.mapNotNull { byLabel[it] }
                ?: listOf(PulsarTab, DjTab).filter { it in dockablePanels }
        }
    }

    BoxWithConstraints(
        // Edge-to-edge on purpose: no inset padding, so the UI and the VizBackground behind it
        // fill into the display cutout instead of letterboxing below the notch (system bars are
        // hidden in MainActivity; DjAppHeaderRow's own SpaceBetween clears a center punch-hole).
        modifier = modifier
            .fillMaxSize(),
    ) {
        val layoutMode = determineLayoutMode(
            maxWidth,
            maxHeight,
            LocalTvModeAllowed.current,
        )
        val isLandscape = layoutMode != DjLayoutMode.Portrait
        val isLargeScreen = layoutMode == DjLayoutMode.LargeScreen

        // The stage is identical in every layout; only the navigation around it differs.
        val stage: @Composable () -> Unit = {
            // One renderer per route, shared by the nav destinations and the TV dock, so a
            // panel looks the same however it got on screen. showTitle names panels apart
            // when several are docked at once; a lone nav destination needs no title.
            val routePanel: @Composable (DjRoute, Modifier, Boolean) -> Unit =
                { route, panelModifier, docked ->
                    // No panel titles in the dock: the panels are distinct enough by shape
                    // and colour, and the headers cost vertical space on a television.
                    val showTitle = false
                    val fill = !docked
                    when (route) {
                        PulsarTab -> PulsarPanel(
                            pulsar = pulsarFeature,
                            vizFlow = synthEngine.pulsarVizFlow,
                            trackVizFlows = synthEngine.pulsarTrackVizFlows,
                            modifier = panelModifier,
                            isExpanded = true,
                            onExpandedChange = {},
                            showCollapsedHeader = false,
                            showExpandedTitle = showTitle,
                            fillHeight = fill,
                            // TV docks the ending picker as the bottom bar's "Ends" button
                            // instead (routePanel's docked=true only ever happens on TV).
                            showEndingControl = !docked,
                        )
                        DjTab -> DjPanel(
                            feature = djFeature,
                            vizFlowA = synthEngine.djVizFlowA,
                            vizFlowB = synthEngine.djVizFlowB,
                            outVizFlow = synthEngine.djOutVizFlow,
                            beatPhaseFlow = synthEngine.beatPhaseFlow,
                            modifier = panelModifier,
                            isExpanded = true,
                            onExpandedChange = {},
                            showCollapsedHeader = false,
                            showExpandedTitle = showTitle,
                            fillHeight = fill,
                        )
                        TimerTab -> TimerPanel(
                            modifier = panelModifier,
                            showCollapsedHeader = false,
                            showExpandedTitle = showTitle,
                            fillHeight = fill,
                        )
                        // Docked, Mix is the mixer alone; the nav destination keeps the
                        // reverb strip above it, where there is room for both.
                        MixTab -> Column(modifier = panelModifier) {
                            if (!docked) ReverbPanel(
                                inVizFlow = synthEngine.reverbInVizFlow,
                                outVizFlow = synthEngine.reverbOutVizFlow,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                isExpanded = true,
                                onExpandedChange = {},
                                showCollapsedHeader = false,
                                showExpandedTitle = showTitle,
                                fillHeight = fill,
                            )
                            MixerPanel(
                                feature = mixerFeature,
                                trackVizFlows = synthEngine.pulsarTrackVizFlows,
                                masterOutVizFlow = synthEngine.masterOutVizFlow,
                                modifier = Modifier.fillMaxWidth(),
                                isExpanded = true,
                                onExpandedChange = {},
                                showCollapsedHeader = false,
                                showExpandedTitle = showTitle,
                            fillHeight = fill,
                            )
                        }
                        HornTab -> HornPanel(
                            inVizFlow = synthEngine.hornInVizFlow,
                            outVizFlow = synthEngine.hornOutVizFlow,
                            hornPhaseVizFlow = synthEngine.hornPhaseVizFlow,
                            wooferPhaseVizFlow = synthEngine.wooferPhaseVizFlow,
                            modifier = panelModifier,
                            isExpanded = true,
                            onExpandedChange = {},
                            showCollapsedHeader = false,
                            showExpandedTitle = showTitle,
                            fillHeight = fill,
                        )
                        VibeInfoTab -> VibeInfoPanel(
                            pulsar = pulsarFeature,
                            vizFlow = synthEngine.pulsarVizFlow,
                            modifier = panelModifier,
                            fillHeight = fill,
                        )
                        EndsTab -> {
                            val songEndingEnabled by pulsarFeature.actions.songEndingEnabled.collectAsState()
                            val transitionSpec by pulsarFeature.actions.transitionSpec.collectAsState()
                            EndsPanel(
                                spec = transitionSpec,
                                enabled = songEndingEnabled,
                                onSetEnabled = pulsarFeature.actions.onSetSongEndingEnabled,
                                onStyleChange = pulsarFeature.actions.onSetTransitionStyle,
                                onHandoffMsChange = pulsarFeature.actions.onSetTransitionHandoffMs,
                                modifier = panelModifier,
                                isExpanded = true,
                                onExpandedChange = {},
                                showCollapsedHeader = false,
                                showExpandedTitle = showTitle,
                                fillHeight = fill,
                            )
                        }
                        else -> Unit
                    }
                }

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
                            entry<DjTab> { routePanel(DjTab, Modifier.fillMaxSize(), false) }
                            entry<TimerTab> { routePanel(TimerTab, Modifier.fillMaxSize(), false) }
                            entry<MixTab> { routePanel(MixTab, Modifier.fillMaxSize(), false) }
                            entry<HornTab> { routePanel(HornTab, Modifier.fillMaxSize(), false) }
                        },
                    )
                }
            }

            if (isLargeScreen) {
                // TV: the visualization owns the screen and panels dock around its edges.
                // Nothing fills the centre, so the VizBackground sibling reads through.
                DjPanelDock(
                    panels = dockedPanels.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                ) { route, panelModifier ->
                    routePanel(route, panelModifier, true)
                }
            } else if (isLandscape) {
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
            if (activeSheet == VibeInfoTab && !isLargeScreen) {
                VibeInfoSheet(
                    pulsar = pulsarFeature,
                    vizFlow = synthEngine.pulsarVizFlow,
                    onDismiss = { activeSheet = null },
                )
            }

            // Contributions stay composed while closed (isOpen tracks activeSheet) so they can
            // cancel in-flight work on close — see DjTabContribution.Content's kdoc.
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

        if (isLargeScreen) {
            // TV layout: top bar (global actions) + bottom bar (panel toggles) around the stage.
            // Provides the TV compositionLocals shared widgets and docked panels read — see each
            // local's own kdoc (LocalTvFocusChrome, LocalTvFocusRegion, LocalTelevisionHardware)
            // for what it gates. remember: the holder must survive recomposition or focus resets.
            val focusRegion = remember { TvFocusRegionHolder() }
            CompositionLocalProvider(
                LocalTvFocusChrome provides true,
                LocalTvFocusRegion provides focusRegion,
                LocalTelevisionHardware provides isTelevisionHardware(),
            ) {
                // Renders nothing — owns only the idle-fade coroutine. Kept as its own composable
                // (not inlined here) so recomposing it on every key event never re-invokes the
                // Column below, let alone Pulsar or any docked panel.
                TvFocusIdleWatcher(focusRegion)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // Tunnels through here before reaching whatever's focused — this ONLY
                        // timestamps activity and always returns false, so it never consumes the
                        // event or otherwise changes behavior. Confirmed safe: nothing else in
                        // this tree uses onPreviewKeyEvent, and every D-pad adjust-mode handler
                        // (RotaryKnob, SegmentedAlgoKnob, BenderFaderWidget) uses onKeyEvent,
                        // which fires during the later bubbling phase exactly as before.
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) focusRegion.notifyActivity()
                            false
                        },
                ) {
                    DjTvTopBar(
                        vizFeature = vizFeature,
                        pulsarFeature = pulsarFeature,
                        onTogglePlayback = onTogglePlayback,
                        // The bar's top/left/right edges are all physical screen edges here.
                        modifier = Modifier.windowInsetsPadding(
                            platformSafeAreaInsets().only(
                                WindowInsetsSides.Top + WindowInsetsSides.Start +
                                    WindowInsetsSides.End
                            )
                        ),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { stage() }
                    DjTvBottomBar(
                        // Sheet-only tab contributions (e.g. AI) have no dock slot of their own;
                        // appending them here is their only entry point on this layout. Branch on
                        // dockablePanels membership, NOT route.opensAsSheet — VibeInfoTab also has
                        // opensAsSheet=true (it governs only the phone/tablet path per its own
                        // kdoc) but IS in dockablePanels, so it must keep toggling the dock, not
                        // activeSheet.
                        panels = bottomBarPanels(dockablePanels) + tabs.filter { it.opensAsSheet },
                        isDocked = { route ->
                            if (route in dockablePanels) route in dockedPanels.orEmpty()
                            else route == activeSheet
                        },
                        onToggle = { route ->
                            if (route in dockablePanels) {
                                toggleDocked(route)
                            } else {
                                activeSheet = if (activeSheet == route) null else route
                            }
                        },
                        timerFeature = timerFeature,
                        pulsarFeature = pulsarFeature,
                        // Bottom/left/right edges are all physical screen edges here.
                        modifier = Modifier.windowInsetsPadding(
                            platformSafeAreaInsets().only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Start +
                                    WindowInsetsSides.End
                            )
                        ),
                    )
                }
            }
        } else {
            DjAppNavScaffold(
                isSelected = { route ->
                    when {
                        route.opensAsSheet -> route == activeSheet
                        else -> route == currentRoute
                    }
                },
                onItemClick = { route ->
                    when {
                        // Toggle: tapping the active sheet's nav item closes it, otherwise open
                        // (replacing whatever sheet was open).
                        route.opensAsSheet -> activeSheet = if (activeSheet == route) null else route
                        route != currentRoute -> {
                            backStack.clear()
                            backStack.add(route)
                        }
                    }
                },
                layoutMode = layoutMode,
                pulsarFeature = pulsarFeature,
                timerFeature = timerFeature,
                onTogglePlayback = onTogglePlayback,
                tabs = tabs,
                modifier = Modifier.fillMaxSize(),
            ) {
                stage()
            }
        }
    }
}

/**
 * Owns the TV region-focus idle-fade lifecycle for [holder]: one coroutine, restarted every time
 * [TvFocusRegionHolder.activityTick] changes (bumped by the TV layout root's onPreviewKeyEvent
 * above) — not a per-frame clock. This composable renders nothing and reads nothing else, so
 * recomposing it once per key event never re-invokes the Column, the stage, or any docked panel;
 * [TvFocusRegionHolder.alpha] is read back exclusively inside tvFocusRegionBorder's draw phase, so
 * the fade animation itself never recomposes anything either.
 *
 * snapTo(1f) on every restart, rather than animating back in, is deliberate: getting the border
 * back is more important than how it returns — a user pressing a direction must never wonder
 * where focus went. Only the fade OUT after [TvFocusIdleTimeoutMs] of silence animates, over
 * [TvFocusFadeOutMs].
 */
@Composable
private fun TvFocusIdleWatcher(holder: TvFocusRegionHolder) {
    LaunchedEffect(holder.activityTick) {
        holder.alpha.snapTo(1f)
        delay(TvFocusIdleTimeoutMs)
        holder.alpha.animateTo(0f, animationSpec = tween(TvFocusFadeOutMs))
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
        // Clears the Dynamic Island (status bar hidden, but the Island still reserves top safe
        // area) — resolves to zero on Android/desktop, so edge-to-edge stays unchanged there.
        // VizBackground behind this still fills the cutout; only this foreground chrome is inset.
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
internal fun VizDropdown(
    vizFeature: VizFeature,
    modifier: Modifier = Modifier,
    // TV's top bar passes bigger values; every other caller keeps these defaults, so the phone
    // header's sizing is untouched.
    height: Dp = 36.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val fullState by vizFeature.stateFlow.collectAsState()
    val vizName by remember { derivedStateOf { fullState.selectedViz.name } }
    val isRandom by remember { derivedStateOf { fullState.isRandomVizMode } }
    val visualizations by remember { derivedStateOf { fullState.visualizations } }
    val vizActions = vizFeature.actions
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .height(height)
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
                    style = textStyle,
                    color = effects.title.titleColor.readableOnDark(),
                    maxLines = 1
                )
                Text(
                    text = if (expanded) " ▲" else " ▼",
                    style = textStyle,
                    color = effects.title.titleColor.readableOnDark(),
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
@Preview(widthDp = 360, heightDp = 780, name = "DJ Tab 140%", fontScale = 1.4f)
@Composable
private fun DjTabPreview() {
    OrpheusTheme {
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
}

@Preview(widthDp = 360, heightDp = 780, name = "Mix Tab")
@Preview(widthDp = 360, heightDp = 780, name = "Mix Tab 140%", fontScale = 1.4f)
@Composable
private fun MixTabPreview() {
    OrpheusTheme {
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
}

@Preview(widthDp = 360, heightDp = 780, name = "Horn Tab")
@Preview(widthDp = 360, heightDp = 780, name = "Horn Tab 140%", fontScale = 1.4f)
@Composable
private fun HornTabPreview() {
    OrpheusTheme {
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
}

@Preview(widthDp = 360, heightDp = 780, name = "Timer Tab")
@Preview(widthDp = 360, heightDp = 780, name = "Timer Tab 140%", fontScale = 1.4f)
@Composable
private fun TimerTabPreview() {
    OrpheusTheme {
        DjAppPreviewLayout(selectedTab = TimerTab) { mod ->
            TimerPanel(
                feature = TimerViewModel.previewFeature(),
                modifier = mod,
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Running")
@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Running 140%", fontScale = 1.4f)
@Composable
private fun DjAppNavTimerRunningPreview() {
    OrpheusTheme {
        val runningTimer = TimerViewModel.previewFeature(
            TimerUiState(
                initialTime = 45.minutes,
                remainingTime = 42.minutes.plus(13.seconds),
                status = TimerStatus.RUNNING,
            ),
        )
        DjAppNavScaffold(
            isSelected = { it == DjTab },
            onItemClick = {},
            layoutMode = DjLayoutMode.Portrait,
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
}

@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Paused")
@Preview(widthDp = 360, heightDp = 780, name = "DJ Nav — Timer Paused 140%", fontScale = 1.4f)
@Composable
private fun DjAppNavTimerPausedPreview() {
    OrpheusTheme {
        val pausedTimer = TimerViewModel.previewFeature(
            TimerUiState(
                initialTime = 45.minutes,
                remainingTime = 12.minutes,
                status = TimerStatus.PAUSED,
            ),
        )
        DjAppNavScaffold(
            isSelected = { it == DjTab },
            onItemClick = {},
            layoutMode = DjLayoutMode.Portrait,
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
}

// ── Landscape Previews ──

@Preview(widthDp = 780, heightDp = 360, name = "DJ Tab — Landscape")
@Preview(widthDp = 780, heightDp = 360, name = "DJ Tab — Landscape 140%", fontScale = 1.4f)
@Composable
private fun DjTabLandscapePreview() {
    OrpheusTheme {
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
}

@Preview(widthDp = 780, heightDp = 360, name = "Mix Tab — Landscape")
@Preview(widthDp = 780, heightDp = 360, name = "Mix Tab — Landscape 140%", fontScale = 1.4f)
@Composable
private fun MixTabLandscapePreview() {
    OrpheusTheme {
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
}

@Preview(widthDp = 780, heightDp = 360, name = "Horn Tab — Landscape")
@Preview(widthDp = 780, heightDp = 360, name = "Horn Tab — Landscape 140%", fontScale = 1.4f)
@Composable
private fun HornTabLandscapePreview() {
    OrpheusTheme {
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
}
