package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.features.SynthFeatureRegistry
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.liquefiableVizEffects
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.viz.LocalSignalVizEnabled
import org.balch.orpheus.ui.viz.LocalSignalVizGlow
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.PanelIdleFade
import org.balch.orpheus.ui.viz.PanelKeyBridge
import org.balch.orpheus.ui.viz.VizStage
import org.balch.orpheus.ui.viz.handlePanelKey
import org.balch.orpheus.ui.widgets.VizBackground

@Composable
fun DjApp(
    graph: DjAppGraph,
    onTogglePlayback: () -> Unit = {},
    /**
     * Starts the audio engine and applies the DJ voicing. Android Auto and iOS
     * start audio outside the composition, so each platform passes its own.
     */
    startAudio: suspend () -> Unit,
    /**
     * A host window's key hook, if it has one. Desktop needs it: Compose routes keys along the
     * focus path, so a window nobody has clicked yet never reaches the root observer below and
     * no key would bring the faded panels back. Android, iOS and wasm leave it null.
     */
    panelKeys: PanelKeyBridge? = null,
    updateOverlay: @Composable BoxScope.() -> Unit = {},
) {
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.metroViewModelFactory,
    ) {
        val registry: SynthFeatureRegistry = metroViewModel()

        CompositionLocalProvider(LocalSynthFeatures provides registry) {
            val liquidState = rememberLiquidState()
            val dialogLiquidState = rememberLiquidState()
            val vizFeature: VizFeature = registry.feature<VizFeature>()
            val vizState by vizFeature.stateFlow.collectAsStateWithLifecycle()
            val liquidEffects = vizState.liquidEffects

            // Start audio engine and enable viz.
            //
            // Engine start is idempotent (SynthOrchestrator.isStarted guard).
            // Android Auto also starts the engine from DjMediaBrowserService
            // .onCreate so audio works when no Activity is composed. On the
            // launcher path (no service bound yet) this LaunchedEffect is the
            // start trigger. The JVM desktop path has no service, so this is
            // its only call there; iOS also reaches DjAppHost.startAudio()
            // from a cold-launch widget tap, entirely outside this composition.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.Default) {
                    startAudio()
                    graph.synthEngine.setTurntableVizEnabled(true)
                }
            }

            // Pick a new random visualization on each vibe transition
            val pulsarFeature: PulsarFeature = registry.feature<PulsarFeature>()
            LaunchedEffect(Unit) {
                pulsarFeature.vibeFlow
                    .distinctUntilChangedBy { it.name }
                    .drop(1)
                    .collect {
                        if (vizFeature.stateFlow.value.isRandomVizMode) {
                            vizFeature.actions.onSelectRandomViz()
                        }
                    }
            }

            // Enable per-panel signal viz when Orphoscope is active
            val isSignalMonitor = vizState.selectedViz.id == "signal-monitor"

            // Only run the heavy 24-channel signal-scope poll while it actually
            // feeds something on screen (the Orphoscope viz or the per-panel
            // traces). In every other viz mode it would burn CPU + GC for nobody.
            // Mirrors the full Orpheus app (App.kt).
            val signalVizActive = isSignalMonitor || vizState.signalVizEnabled
            LaunchedEffect(signalVizActive) {
                graph.synthEngine.setVizEnabled(signalVizActive)
            }

            // Snapshot the DI multibinding once. This scope recomposes on every vizState change,
            // and toList() would otherwise allocate a fresh list each frame for a set that is
            // fixed for the graph's lifetime.
            val tabContributions = remember(graph) { graph.djTabContributions.toList() }

            // The stage (where the picture may go) and the panels' idle fade are shared between
            // the visualization and the screen, so both are owned here, above the two of them.
            val vizStage = remember { VizStage() }
            val panelFade = remember { PanelIdleFade() }
            val hidesPanelsWhenIdle = vizState.selectedViz.hidesPanelsWhenIdle

            // The window hook and the root observer run the same rule, so a key behaves the same
            // whichever path reaches it first.
            DisposableEffect(panelKeys, panelFade, hidesPanelsWhenIdle) {
                panelKeys?.connect { event -> panelFade.handlePanelKey(event, hidesPanelsWhenIdle) }
                onDispose { panelKeys?.connect(null) }
            }

            OrpheusTheme {
                CompositionLocalProvider(
                    LocalLiquidState provides liquidState,
                    LocalDialogLiquidState provides dialogLiquidState,
                    LocalLiquidEffects provides liquidEffects,
                    LocalSignalVizEnabled provides isSignalMonitor,
                    LocalSignalVizGlow provides (1f - vizState.knob2Value),
                    LocalVizStage provides vizStage,
                    LocalPanelIdleFade provides panelFade,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .panelActivityObserver(panelFade, hidesPanelsWhenIdle),
                    ) {
                        // Outer liquefiable: source for the dialog lens. It must
                        // include both the viz AND the panels so dialogs see
                        // through everything.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquefiableVizEffects(dialogLiquidState)
                        ) {
                            // VizBackground is the source for the panel lenses
                            // (liquidState). It's a SIBLING of DjAppScreen, not
                            // a parent, because otherwise the panels would be inside
                            // their own source and the glass effect collapses.
                            VizBackground(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .liquefiableVizEffects(liquidState),
                                selectedViz = vizState.selectedViz,
                            )
                            DjAppScreen(
                                synthEngine = graph.synthEngine,
                                vizFeature = vizFeature,
                                appPreferencesRepository = graph.appPreferencesRepository,
                                onTogglePlayback = onTogglePlayback,
                                modifier = Modifier.fillMaxSize(),
                                tabContributions = tabContributions,
                                hidesPanelsWhenIdle = hidesPanelsWhenIdle,
                            )
                        }

                        // In-app update banner (Android wires a host here; no-op elsewhere)
                        updateOverlay()
                    }
                }
            }
        }
    }
}

/**
 * Restarts the panels' idle countdown on deliberate input. The pointer observer runs in the
 * Initial pass and never consumes, so no gesture changes meaning. The key observer defers to
 * [handlePanelKey], which passes every key on except a confirm key pressed while the panels are
 * not fully drawn: that one only wakes them, because activating an unseen control is worse than
 * one press that moves nothing.
 *
 * The TV layout has its own key observer (DjAppScreen's LargeScreen branch) running the same
 * rule; on a television there are no pointer events at all.
 */
internal fun Modifier.panelActivityObserver(fade: PanelIdleFade, enabled: Boolean): Modifier =
    if (!enabled) this else this
        .pointerInput(fade) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.isPanelActivity()) fade.notifyActivity()
                }
            }
        }
        .onPreviewKeyEvent { event -> fade.handlePanelKey(event) }

/**
 * What counts as the user doing something: a press, a scroll, and anything at all while a button
 * or a finger is down, so a knob drag longer than the timeout holds the panels up for its whole
 * length. Hovering does not count: the pointer crossing the window is not a decision, and the
 * user asked for the panels to stay away until a click or a key.
 *
 * [PointerInputChange.previousPressed] is what catches the release that ends a drag: by then
 * `pressed` is already false, and the release is the moment the countdown should start from.
 */
private fun PointerEvent.isPanelActivity(): Boolean =
    type == PointerEventType.Scroll || changes.any { it.pressed || it.previousPressed }
