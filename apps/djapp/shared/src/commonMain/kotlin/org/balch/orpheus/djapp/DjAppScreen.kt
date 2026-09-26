package org.balch.orpheus.djapp

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.djapp.variant.mergeTabContributions
import org.balch.orpheus.djapp.vibeinfo.VibeInfoPanel
import org.balch.orpheus.djapp.vibeinfo.VibeInfoSheet
import org.balch.orpheus.features.dj.DjPanel
import org.balch.orpheus.features.dj.DjUiState
import org.balch.orpheus.features.dj.DjViewModel
import org.balch.orpheus.features.horn.HornDisplayHeight
import org.balch.orpheus.features.horn.HornPanel
import org.balch.orpheus.features.horn.HornViewModel
import org.balch.orpheus.features.pulsar.EndsPanel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarGridHeight
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.mixer.MixerPanel
import org.balch.orpheus.features.pulsar.mixer.MixerViewModel
import org.balch.orpheus.features.timer.TimerFeature
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
import org.balch.orpheus.ui.viz.KeepPanelsAwake
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.PanelIdleFadeWatcher
import org.balch.orpheus.ui.viz.PanelWakeOverlay
import org.balch.orpheus.ui.viz.panelIdleFade
import org.balch.orpheus.ui.viz.vizStage
import org.balch.orpheus.ui.viz.vizStageChrome
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
    // Passed in rather than collected here: DjApp already watches the viz state, and collecting
    // it again would recompose this whole screen on every viz knob turn.
    hidesPanelsWhenIdle: Boolean = false,
) {
    val djFeature = DjViewModel.feature()
    val pulsarFeature = PulsarViewModel.feature()
    val timerFeature = TimerViewModel.feature()
    val mixerFeature = MixerViewModel.feature()
    val scope = rememberCoroutineScope()

    // Mapped before collecting: DjUiState changes on every platter tick, and only this flips the fade.
    val turntableUp by remember(djFeature) {
        djFeature.stateFlow.map(::anyTurntableUp).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = anyTurntableUp(djFeature.stateFlow.value))

    // Nav3 back stack: single-level tab switching
    val backStack = remember { NavBackStack<DjRoute>(DjTab) }
    val currentRoute = backStack.lastOrNull() ?: DjTab
    val tabs = remember(tabContributions) { mergeTabContributions(djTabs, tabContributions) }

    // Single state for "which sheet is open" (a tab-sheet contribution's route, VibeInfoTab, or
    // null): one value replacing itself needs no separate "close the other sheet" bookkeeping.
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
        // Unbounded: the dock never evicts. togglePanel's append is what makes toggle order the
        // slot order.
        val next = togglePanel(dockedPanels.orEmpty(), route)
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

    // The wide-portrait bottom pair. Its own list rather than dockedPanels: the pair holds two
    // and evicts the oldest, and a shared list would let that eviction undock a landscape panel.
    var pairPanels by remember { mutableStateOf<List<DjRoute>?>(null) }
    val pairablePanels = remember(tabs) { portraitPairPanels(tabs) }

    val togglePair: (DjRoute) -> Unit = { route ->
        val next = togglePanel(pairPanels.orEmpty(), route, capacity = PortraitPairCapacity)
        pairPanels = next
        scope.launch {
            appPreferencesRepository.update {
                it.copy(portraitPairPanels = next.map(DjRoute::label))
            }
        }
    }

    LaunchedEffect(pairablePanels) {
        val byLabel = pairablePanels.associateBy { it.label }
        val saved = appPreferencesRepository.load().portraitPairPanels
        // Same guard as the dock: a tap during this suspend already claimed the value.
        if (pairPanels == null) {
            pairPanels = saved?.mapNotNull { byLabel[it] }?.take(PortraitPairCapacity)
                ?: DefaultPortraitPair.filter { it in pairablePanels }
        }
    }

    DjLayoutBox(
        // Edge-to-edge on purpose: no inset padding, so the UI and the VizBackground behind it
        // fill into the display cutout instead of letterboxing below the notch (system bars are
        // hidden in MainActivity; DjAppHeaderRow's own SpaceBetween clears a center punch-hole).
        modifier = modifier
            .fillMaxSize(),
    ) { layout ->
        val panelFade = LocalPanelIdleFade.current
        // One expression for the whole feature, read inside the layout box so a window crossing
        // the dock threshold turns it off in the same composition that picks the new layout.
        val fadeEnabled = fadesPanelsWhenIdle(
            layout,
            hidesPanelsWhenIdle,
            sheetOpen = activeSheet != null,
            turntableUp = turntableUp,
        )
        PanelIdleFadeWatcher(fade = panelFade, enabled = fadeEnabled)
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
                            // The dock has the ending picker as the top bar's "Ends" toggle
                            // instead (routePanel's docked=true only ever happens in the dock).
                            showEndingControl = !docked,
                            // The dock's top bar carries the one Vibe picker (and the anomaly
                            // long-press); the docked panel drops its own duplicate chip.
                            showVibePicker = !docked,
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
                        MixTab -> Column(modifier = panelModifier) {
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
                        HornTab -> {
                            // Only a filled slot is read back: its height comes from the layout,
                            // not the content. A docked panel wraps its content and keeps 160dp.
                            var slotPx by remember { mutableIntStateOf(0) }
                            val density = LocalDensity.current
                            val displayHeight = if (!fill || slotPx == 0) HornDisplayHeight else {
                                hornDisplayHeightFor(with(density) { slotPx.toDp() }, HornDisplayHeight)
                            }
                            HornPanel(
                                inVizFlow = synthEngine.hornInVizFlow,
                                outVizFlow = synthEngine.hornOutVizFlow,
                                hornPhaseVizFlow = synthEngine.hornPhaseVizFlow,
                                wooferPhaseVizFlow = synthEngine.wooferPhaseVizFlow,
                                modifier = panelModifier.onSizeChanged { slotPx = it.height },
                                isExpanded = true,
                                onExpandedChange = {},
                                showCollapsedHeader = false,
                                showExpandedTitle = showTitle,
                                fillHeight = fill,
                                displayHeight = displayHeight,
                            )
                        }
                        VibeInfoTab -> VibeInfoPanel(
                            pulsar = pulsarFeature,
                            vizFlow = synthEngine.pulsarVizFlow,
                            modifier = panelModifier,
                            fillHeight = fill,
                        )
                        EndsTab -> {
                            val songEndingEnabled by pulsarFeature.actions.songEndingEnabled.collectAsStateWithLifecycle()
                            val transitionSpec by pulsarFeature.actions.transitionSpec.collectAsStateWithLifecycle()
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

            DjAppMainContent(
                layout = layout,
                dockedPanels = dockedPanels.orEmpty(),
                pairPanels = pairPanels.orEmpty(),
                pulsarFeature = pulsarFeature,
                synthEngine = synthEngine,
                vizFeature = vizFeature,
                // With room for a pair, Info earns a slot instead of covering the screen.
                onShowVibeInfo = {
                    if (layout.showsPair()) togglePair(VibeInfoTab) else activeSheet = VibeInfoTab
                },
                routePanel = routePanel,
                navContent = navContent,
            )

            DjAppOverlaySheets(
                activeSheet = activeSheet,
                layout = layout,
                pulsarFeature = pulsarFeature,
                synthEngine = synthEngine,
                tabContributions = tabContributions,
                onDismiss = { activeSheet = null },
            )
        }

        when (layout) {
            DjLayout.LargeScreen -> {
                // remember: the holder must survive recomposition or focus resets.
                val focusRegion = remember { TvFocusRegionHolder() }
                // Read once and passed down: DjAppTvChrome needs it for LocalTelevisionHardware and
                // the bar glass needs it for its own gate, and they must not disagree.
                val tvHardware = isTelevisionHardware()
                DjAppTvChrome(
                    tvHardware = tvHardware,
                    domeRingSize = dockDomeRingSize(television = tvHardware),
                    barGlass = shouldShowTvBarGlass(layout, tvHardware),
                    vizHidesPanelsWhenIdle = hidesPanelsWhenIdle,
                    focusRegion = focusRegion,
                    vizFeature = vizFeature,
                    pulsarFeature = pulsarFeature,
                    timerFeature = timerFeature,
                    onTogglePlayback = onTogglePlayback,
                    dockablePanels = dockablePanels,
                    dockedPanels = dockedPanels.orEmpty(),
                    activeSheet = activeSheet,
                    tabs = tabs,
                    onToggleDocked = toggleDocked,
                    onActiveSheetChange = { activeSheet = it },
                    stage = stage,
                )
            }
            DjLayout.Portrait, DjLayout.PortraitPair, DjLayout.Landscape, is DjLayout.Tabletop -> {
                DjAppNavScaffold(
                    isSelected = { route ->
                        when {
                            route.opensAsSheet -> route == activeSheet
                            // Both halves of the pair light up, the way docked panels do on TV.
                            layout.showsPair() -> route in pairPanels.orEmpty()
                            else -> route == currentRoute
                        }
                    },
                    onItemClick = { route ->
                        when {
                            // Toggle: tapping the active sheet's nav item closes it, otherwise open
                            // (replacing whatever sheet was open).
                            route.opensAsSheet -> activeSheet = if (activeSheet == route) null else route
                            // Pair layouts: the nav is a toggle bar, not single-select navigation.
                            layout.showsPair() -> togglePair(route)
                            route != currentRoute -> {
                                backStack.clear()
                                backStack.add(route)
                            }
                        }
                    },
                    layout = layout,
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

        // Last child of the layout box, so it sits over whatever chrome that layout drew, and
        // gated on the same expression as the fade: the frame that picks the dock is the frame
        // that stops blocking input.
        PanelWakeOverlay(fade = panelFade, stage = LocalVizStage.current, enabled = fadeEnabled)
    }
}

/**
 * Whether the panels fade when the user goes quiet.
 *
 * The dock layout is out: there the bottom bar's toggles are what show and hide panels, and a
 * fade would both argue with that and hide controls the user parked there deliberately. An open
 * sheet lives in its own window and would not fade with the panels' alpha, so it stops the clock
 * too; dropdown popups do the same through the holder's own modal count (see KeepPanelsAwake).
 * A turntable fader left up means the user is mid-mix, so the deck stays in view.
 */
internal fun fadesPanelsWhenIdle(
    layout: DjLayout,
    vizOptsIn: Boolean,
    sheetOpen: Boolean,
    turntableUp: Boolean,
): Boolean = when (layout) {
    DjLayout.LargeScreen -> false
    DjLayout.Portrait, DjLayout.PortraitPair, DjLayout.Landscape, is DjLayout.Tabletop ->
        vizOptsIn && !sheetOpen && !turntableUp
}

/** Either deck's level fader above the floor. */
internal fun anyTurntableUp(state: DjUiState): Boolean = state.wetA > 0f || state.wetB > 0f

/**
 * The stage for each [DjLayout]; tabletop puts Pulsar above the hinge and everything else below.
 *
 * Internal rather than private so `DjAppMainContentWiringTest` can drive the real thing: the stage
 * report and the idle fade are per-branch modifiers, and a test on a stand-in layout would pass
 * with either of them deleted.
 */
@Composable
internal fun DjAppMainContent(
    layout: DjLayout,
    dockedPanels: List<DjRoute>,
    pairPanels: List<DjRoute>,
    pulsarFeature: PulsarFeature,
    synthEngine: SynthEngine,
    vizFeature: VizFeature,
    onShowVibeInfo: () -> Unit,
    routePanel: @Composable (DjRoute, Modifier, Boolean) -> Unit,
    navContent: @Composable (Modifier) -> Unit,
) {
    // The container holding the content panels is both what the picture is sized against and
    // what the idle fade applies to; the header and the nav are outside it in every layout.
    val stage = LocalVizStage.current
    val fade = LocalPanelIdleFade.current

    // Below header and Pulsar: the pair when the layout has room, else the nav-selected panel.
    val lowerPanels: @Composable (Modifier) -> Unit = { mod ->
        if (layout.showsPair()) {
            PortraitPanelPair(panels = pairPanels, routePanel = routePanel, modifier = mod)
        } else {
            navContent(mod)
        }
    }
    when (layout) {
        DjLayout.LargeScreen -> {
            // TV: the visualization owns the screen and panels dock around its edges.
            // Nothing fills the centre, so the VizBackground sibling reads through. The dock
            // already fills exactly the band between the top and bottom bars, so it IS the stage.
            DjPanelDock(
                panels = dockedPanels,
                modifier = Modifier
                    .fillMaxSize()
                    .vizStage(stage)
                    .panelIdleFade(fade),
            ) { route, panelModifier ->
                routePanel(route, panelModifier, true)
            }
        }
        DjLayout.Landscape -> {
            // Landscape: the header spans everything right of the rail, Pulsar left + nav content
            // right below it, so the two panels start level.
            Column(modifier = Modifier.fillMaxSize()) {
                DjAppHeaderRow(
                    vizFeature = vizFeature,
                    onInfoClick = onShowVibeInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    // Top clears flex mode's status bar; the rail already covers the start side.
                    insetSides = WindowInsetsSides.Top + WindowInsetsSides.End,
                )
                // The panels' own container, as in portrait. The fade goes on each panel.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .vizStage(stage),
                ) {
                    // A short screen (the Fold 8's 360dp cover) takes its shortfall off the grid, as
                    // portrait does. The slot's height comes from the row, so reading it back is safe.
                    var pulsarSlotPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val gridHeight = if (pulsarSlotPx == 0) PulsarGridHeight else {
                        pulsarGridHeightFor(with(density) { pulsarSlotPx.toDp() }, PulsarGridHeight)
                    }
                    PulsarPanel(
                        modifier = Modifier
                            .weight(.5f)
                            .fillMaxHeight()
                            .onSizeChanged { pulsarSlotPx = it.height }
                            .panelIdleFade(fade),
                        pulsar = pulsarFeature,
                        vizFlow = synthEngine.pulsarVizFlow,
                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                        isExpanded = true,
                        onExpandedChange = {},
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                        // Top-anchored: centred, a short wide window left ~140dp dead above the selectors.
                        centerContent = false,
                        gridHeight = gridHeight,
                    )
                    navContent(Modifier.weight(.5f).fillMaxHeight().panelIdleFade(fade))
                }
            }
        }
        DjLayout.Portrait, DjLayout.PortraitPair -> {
            // Portrait: Header, Pulsar top, panels bottom
            Column(modifier = Modifier.fillMaxSize()) {
                DjAppHeaderRow(
                    vizFeature = vizFeature,
                    onInfoClick = onShowVibeInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    // A closed iPhone Duo has its camera in a top corner behind a side inset and
                    // no top inset, which would leave the viz picker under the lens.
                    insetSides = WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                )
                // The panels' own container, so the picture knows where they are and the fade
                // reaches them alone. weight(1f) takes exactly what the header leaves, and the
                // .6/.4 split inside it is the same split as before.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .vizStage(stage)
                        .panelIdleFade(fade),
                ) {
                    // The slot's height comes from its weight, not its content, so reading it back
                    // (previous frame, as DjLayoutBox does) cannot feed a layout loop.
                    var pulsarSlotPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val gridHeight = if (pulsarSlotPx == 0) PulsarGridHeight else {
                        pulsarGridHeightFor(with(density) { pulsarSlotPx.toDp() }, PulsarGridHeight)
                    }
                    PulsarPanel(
                        pulsar = pulsarFeature,
                        vizFlow = synthEngine.pulsarVizFlow,
                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                        modifier = Modifier
                            .weight(.6f)
                            .fillMaxWidth()
                            .onSizeChanged { pulsarSlotPx = it.height },
                        isExpanded = true,
                        onExpandedChange = {},
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                        gridHeight = gridHeight,
                    )
                    lowerPanels(Modifier.weight(.4f).fillMaxWidth())
                }
            }
        }
        is DjLayout.Tabletop -> {
            // Pulsar alone fills the upright half. The header's title and viz picker are touch
            // controls, so they sit on the flat half with the panels (render sweep: no room above).
            //
            // Its header is the one that sits INSIDE the reported stage, between the two halves,
            // so it is reported separately and the wake overlay leaves it live. Cleared on the way
            // out, or the next layout would keep a hole where this header used to be.
            DisposableEffect(stage) { onDispose { stage?.reportChromeBand(null) } }
            DjTabletopLayout(
                hinge = layout.hinge,
                // The whole folded region is the stage: the hinge splits the panels, not the
                // picture, and the set is happier spanning it than squeezed into one half.
                modifier = Modifier.vizStage(stage),
                top = { mod ->
                    PulsarPanel(
                        pulsar = pulsarFeature,
                        vizFlow = synthEngine.pulsarVizFlow,
                        trackVizFlows = synthEngine.pulsarTrackVizFlows,
                        modifier = mod.panelIdleFade(fade),
                        isExpanded = true,
                        onExpandedChange = {},
                        showCollapsedHeader = false,
                        showExpandedTitle = false,
                    )
                },
                bottom = { mod ->
                    Column(mod) {
                        DjAppHeaderRow(
                            vizFeature = vizFeature,
                            onInfoClick = onShowVibeInfo,
                            // Reported first in the chain, so the live band covers the padding too.
                            modifier = Modifier
                                .vizStageChrome(stage)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        lowerPanels(Modifier.weight(1f).fillMaxWidth().panelIdleFade(fade))
                    }
                },
            )
        }
    }
}

/**
 * Up to [PortraitPairCapacity] panels side by side, in the order the user chose them. One panel
 * takes the full width; none leaves the row empty so the visualization shows through, the same
 * as an empty dock.
 */
@Composable
private fun PortraitPanelPair(
    panels: List<DjRoute>,
    routePanel: @Composable (DjRoute, Modifier, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        panels.forEach { route ->
            // Keyed by route: after an eviction the survivor slides from the right slot to the
            // left, and without a key it would inherit the evicted panel's remembered state.
            key(route) {
                routePanel(route, Modifier.weight(1f).fillMaxHeight(), false)
            }
        }
    }
}

/**
 * Modal overlays layered on top of the main content: the Vibe Info sheet (phone and tablet only,
 * TV docks it as a panel instead) and any tab contributions that open as sheets (e.g. AI).
 */
@Composable
private fun DjAppOverlaySheets(
    activeSheet: DjRoute?,
    layout: DjLayout,
    pulsarFeature: PulsarFeature,
    synthEngine: SynthEngine,
    tabContributions: List<DjTabContribution>,
    onDismiss: () -> Unit,
) {
    // The dock shows Vibe Info as a panel, and the pair layouts put it in the pair instead of
    // setting activeSheet. The single-panel layouts open it as this sheet.
    val vibeInfoIsSheet = when (layout) {
        DjLayout.LargeScreen -> false
        DjLayout.Portrait, DjLayout.PortraitPair, DjLayout.Landscape, is DjLayout.Tabletop -> true
    }
    // VibeInfo is title-triggered, not a tab contribution, so it keeps its dedicated
    // composable, but shares the single activeSheet state.
    if (activeSheet == VibeInfoTab && vibeInfoIsSheet) {
        VibeInfoSheet(
            pulsar = pulsarFeature,
            vizFlow = synthEngine.pulsarVizFlow,
            onDismiss = onDismiss,
        )
    }

    // Contributions stay composed while closed (isOpen tracks activeSheet) so they can
    // cancel in-flight work on close. See DjTabContribution.Content's kdoc.
    tabContributions.forEach { contribution ->
        if (contribution.route.opensAsSheet) {
            contribution.Content(
                isOpen = activeSheet == contribution.route,
                modifier = Modifier.fillMaxSize(),
                isLandscape = layout.usesLandscapeChrome(),
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The dock: the top bar (Pulsar, Info and Ends toggles, the title, the pickers), the song band,
 * the stage, and the bottom bar (the other toggles around the play/pause dome). Provides the TV
 * compositionLocals shared widgets and docked panels read. See each local's own kdoc
 * (LocalTvFocusChrome, LocalTvFocusRegion, LocalTelevisionHardware) for what it gates.
 *
 * Internal so the dock's scene tests drive the real chrome, draw order and glass included.
 */
@Composable
internal fun DjAppTvChrome(
    tvHardware: Boolean,
    // The bottom bar's centre dome, from dockDomeRingSize.
    domeRingSize: Dp,
    barGlass: Boolean,
    vizHidesPanelsWhenIdle: Boolean,
    focusRegion: TvFocusRegionHolder,
    vizFeature: VizFeature,
    pulsarFeature: PulsarFeature,
    timerFeature: TimerFeature,
    onTogglePlayback: () -> Unit,
    dockablePanels: List<DjRoute>,
    dockedPanels: List<DjRoute>,
    activeSheet: DjRoute?,
    tabs: List<DjRoute>,
    onToggleDocked: (DjRoute) -> Unit,
    onActiveSheetChange: (DjRoute?) -> Unit,
    stage: @Composable () -> Unit,
) {
    // Re-provided here, above both bars and the stage, so the bars and every docked panel agree.
    val ambientEffects = LocalLiquidEffects.current
    val dockEffects = remember(ambientEffects, vizHidesPanelsWhenIdle) {
        dockLiquidEffects(ambientEffects, vizHidesPanelsWhenIdle)
    }
    CompositionLocalProvider(
        LocalTvFocusChrome provides true,
        LocalTvFocusRegion provides focusRegion,
        LocalTelevisionHardware provides tvHardware,
        LocalLiquidEffects provides dockEffects,
    ) {
        // Renders nothing: it owns only the idle-fade coroutine. Kept as its own composable
        // (not inlined here) so recomposing it on every key event never re-invokes the
        // Column below, let alone Pulsar or any docked panel.
        TvFocusIdleWatcher(focusRegion)

        val panelFade = LocalPanelIdleFade.current
        // Sheet-only tab contributions (e.g. AI) have no dock slot of their own; appending them
        // here is their only entry point on this layout. Remembered, so a chrome recomposition
        // that is not the bottom bar's business (a window resize) leaves it free to skip.
        val bottomPanels = remember(dockablePanels, tabs) {
            bottomBarPanels(dockablePanels) + tabs.filter { it.opensAsSheet }
        }
        val topPanels = remember(dockablePanels) { topBarPanels(dockablePanels) }
        // Both bars' toggles. Branch on dockablePanels membership, NOT route.opensAsSheet:
        // VibeInfoTab also has opensAsSheet=true (it governs only the phone/tablet path per its own
        // kdoc) but IS in dockablePanels, so it must keep toggling the dock, not activeSheet.
        val isDocked: (DjRoute) -> Boolean = { route ->
            if (route in dockablePanels) route in dockedPanels else route == activeSheet
        }
        val onToggle: (DjRoute) -> Unit = { route ->
            if (route in dockablePanels) {
                onToggleDocked(route)
            } else {
                onActiveSheetChange(if (activeSheet == route) null else route)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Tunnels through here before reaching whatever's focused. This ONLY
                // timestamps activity and always returns false, so it never consumes the
                // event or otherwise changes behavior. The dome's own preview watchers (the
                // keyboard focus mark's input mode, the wiggle's key cancel) also only watch
                // and return false, and every D-pad adjust-mode handler (RotaryKnob,
                // SegmentedAlgoKnob, BenderFaderWidget) uses onKeyEvent, which fires during
                // the later bubbling phase exactly as before.
                //
                // The dock never fades (see fadesPanelsWhenIdle), so nothing here is ever
                // swallowed: this always returns false. The fade is still told about the press,
                // which only matters for a desktop window later resized below the dock threshold,
                // so it does not arrive with a stale quiet clock and fade out at once.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        focusRegion.notifyActivity()
                        panelFade?.notifyActivity()
                    }
                    false
                },
        ) {
            // Each bar draws its glass to the bezel and keeps its content inside the safe area.
            DjTvTopBar(
                panels = topPanels,
                isDocked = isDocked,
                onToggle = onToggle,
                vizFeature = vizFeature,
                pulsarFeature = pulsarFeature,
                glass = barGlass,
            )
            // Full width under the top bar, off the glass; the stage gives up its height.
            DockSongBand(pulsarFeature)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { stage() }
            // After the stage, so the dome rising out of the bar draws over it and takes its taps
            // first; nothing else in the bar reaches past its top edge.
            DjTvBottomBar(
                panels = bottomPanels,
                isDocked = isDocked,
                onToggle = onToggle,
                timerFeature = timerFeature,
                pulsarFeature = pulsarFeature,
                onTogglePlayback = onTogglePlayback,
                domeRingSize = domeRingSize,
                glass = barGlass,
            )
        }
    }
}

/**
 * Owns the TV region-focus idle-fade lifecycle for [holder]: one coroutine, restarted every time
 * [TvFocusRegionHolder.activityTick] changes (bumped by the TV layout root's onPreviewKeyEvent
 * above), not a per-frame clock. This composable renders nothing and reads nothing else, so
 * recomposing it once per key event never re-invokes the Column, the stage, or any docked panel;
 * [TvFocusRegionHolder.alpha] is read back exclusively inside tvFocusRegionBorder's draw phase, so
 * the fade animation itself never recomposes anything either.
 *
 * snapTo(1f) on every restart, rather than animating back in, is deliberate: getting the border
 * back is more important than how it returns: a user pressing a direction must never wonder
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
internal fun DjAppHeaderRow(
    vizFeature: VizFeature,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
    // Insets are position-blind, so only a header spanning the full window width adds the sides.
    insetSides: WindowInsetsSides = WindowInsetsSides.Top,
) {
    val effects = LocalLiquidEffects.current

    Row(
        // Clears the Dynamic Island (status bar hidden, but the Island still reserves top safe
        // area). Resolves to zero on Android and desktop, so edge-to-edge stays unchanged there.
        // VizBackground behind this still fills the cutout; only this foreground chrome is inset.
        modifier = modifier
            .windowInsetsPadding(platformSafeAreaInsets().only(insetSides))
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
            // The title itself is the Vibe Info trigger (raised, tappable): no separate Info button.
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
    val fullState by vizFeature.stateFlow.collectAsStateWithLifecycle()
    val vizName by remember { derivedStateOf { fullState.selectedViz.name } }
    val isRandom by remember { derivedStateOf { fullState.isRandomVizMode } }
    val visualizations by remember { derivedStateOf { fullState.visualizations } }
    // Locked to the song that owns this visualization: the name still shows, nothing opens.
    val isLocked by remember { derivedStateOf { fullState.isVizLocked } }
    val vizActions = vizFeature.actions
    var expanded by remember { mutableStateOf(false) }

    // A popup does not fade with the panels, so while one is open they stay up.
    if (expanded && !isLocked) KeepPanelsAwake(LocalPanelIdleFade.current)

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
                .clickable(enabled = !isLocked) { expanded = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Same greyed name plus lock glyph, minus the caret, that VizPanel shows.
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked to song",
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = "Viz: " + when {
                        isRandom -> "Random"
                        else -> vizName
                    },
                    style = textStyle,
                    color = if (isLocked) Color.Gray else effects.title.titleColor.readableOnDark(),
                    maxLines = 1
                )
                if (!isLocked) {
                    Text(
                        text = if (expanded) " ▲" else " ▼",
                        style = textStyle,
                        color = effects.title.titleColor.readableOnDark(),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded && !isLocked,
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
 * Previewable layout: portrait (Pulsar top, content bottom) or
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
            MixerPanel(
                feature = MixerViewModel.previewFeature(),
                modifier = mod,
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
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
            layout = DjLayout.Portrait,
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
            layout = DjLayout.Portrait,
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
            MixerPanel(
                feature = MixerViewModel.previewFeature(),
                modifier = mod,
                isExpanded = true,
                onExpandedChange = {},
                showCollapsedHeader = false,
                showExpandedTitle = false,
            )
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
