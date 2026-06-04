package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.dsp.DspSynthEngine
import org.balch.orpheus.core.features.LocalSynthFeatures
import org.balch.orpheus.core.features.SynthFeatureRegistry
import org.balch.orpheus.core.features.feature
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalSignalVizEnabled
import org.balch.orpheus.ui.viz.LocalSignalVizGlow
import org.balch.orpheus.ui.widgets.VizBackground

@Composable
fun DjApp(
    graph: DjAppGraph,
    onTogglePlayback: () -> Unit = {},
    updateOverlay: @Composable BoxScope.() -> Unit = {},
) {
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides graph.metroViewModelFactory,
    ) {
        val registry: SynthFeatureRegistry = metroViewModel()

        CompositionLocalProvider(LocalSynthFeatures provides registry) {
            val liquidState = rememberLiquidState()
            val dialogLiquidState = rememberLiquidState()
            val vizFeature: VizFeature = registry.feature<VizViewModel, VizFeature>()
            val vizState by vizFeature.stateFlow.collectAsState()
            val liquidEffects = vizState.liquidEffects

            // Start audio engine and enable viz.
            //
            // Engine start is idempotent (SynthOrchestrator.isStarted guard).
            // Android Auto also starts the engine from DjMediaBrowserService
            // .onCreate so audio works when no Activity is composed. On the
            // launcher path (no service bound yet) this LaunchedEffect is the
            // start trigger. On the JVM/iOS desktop paths there is no service,
            // so this is the only call.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.Default) {
                    graph.synthOrchestrator.start()

                    graph.synthEngine.setVizEnabled(true)
                    graph.synthEngine.setTurntableVizEnabled(true)

                    // DJ-tuned reverb: short tail, high diffusion for tight space.
                    // Only audible when user dials up a reverb send.
                    // Persistence will override on subsequent launches.
                    val engine = graph.synthEngine
                    if (engine is DspSynthEngine) {
                        engine.setPluginPort("org.balch.orpheus.plugins.reverb", "time",
                            PortValue.FloatValue(0.35f))
                        engine.setPluginPort("org.balch.orpheus.plugins.reverb", "damping",
                            PortValue.FloatValue(0.6f))
                        engine.setPluginPort("org.balch.orpheus.plugins.reverb", "diffusion",
                            PortValue.FloatValue(0.7f))
                    }
                }
            }

            // Pick a new random visualization on each vibe transition
            val pulsarFeature: PulsarFeature = registry.feature<PulsarViewModel, PulsarFeature>()
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

            OrpheusTheme {
                CompositionLocalProvider(
                    LocalLiquidState provides liquidState,
                    LocalDialogLiquidState provides dialogLiquidState,
                    LocalLiquidEffects provides liquidEffects,
                    LocalSignalVizEnabled provides isSignalMonitor,
                    LocalSignalVizGlow provides (1f - vizState.knob2Value),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Outer liquefiable: source for the dialog lens — must
                        // include both the viz AND the panels so dialogs see
                        // through everything.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquefiable(dialogLiquidState)
                        ) {
                            // VizBackground is the source for the panel lenses
                            // (liquidState). It's a SIBLING of DjAppScreen, not
                            // a parent — otherwise the panels would be inside
                            // their own source and the glass effect collapses.
                            VizBackground(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .liquefiable(liquidState),
                                selectedViz = vizState.selectedViz,
                            )
                            DjAppScreen(
                                synthEngine = graph.synthEngine,
                                vizFeature = vizFeature,
                                onTogglePlayback = onTogglePlayback,
                                modifier = Modifier.fillMaxSize(),
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
