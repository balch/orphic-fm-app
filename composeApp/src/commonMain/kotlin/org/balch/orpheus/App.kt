package org.balch.orpheus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.di.OrpheusGraph
import org.balch.orpheus.features.debug.DebugBottomBar
import org.balch.orpheus.features.navigation.AppNavigation
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.VizViewModel
import org.balch.orpheus.ui.widgets.VizBackground

@Composable
fun App(graph: OrpheusGraph) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        val vizViewModel: VizViewModel = metroViewModel()
        val vizState by vizViewModel.uiState.collectAsState()
        val liquidState = rememberLiquidState()

        OrpheusTheme {
            CompositionLocalProvider(
                LocalLiquidState provides liquidState,
                LocalLiquidEffects provides vizState.liquidEffects
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    VizBackground(
                        modifier = Modifier
                            .fillMaxSize()
                            .liquefiable(liquidState)
                    )

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Main Content
                        Box(modifier = Modifier.weight(1f)) {
                            AppNavigation(orchestrator = graph.synthOrchestrator)
                        }

                        // Persistent Debug Bar
                        DebugBottomBar(engine = graph.synthEngine)
                    }
                }
            }
        }
    }
}