package org.balch.orpheus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.di.OrpheusGraph
import org.balch.orpheus.features.debug.DebugBottomBar
import org.balch.orpheus.features.navigation.AppNavigation
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.VizBackground

@Composable
fun App(engine: SynthEngine, graph: OrpheusGraph) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        OrpheusTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                VizBackground()

                Column(modifier = Modifier.fillMaxSize()) {
                    // Main Content
                    Box(modifier = Modifier.weight(1f)) {
                        AppNavigation(orchestrator = graph.synthOrchestrator)
                    }

                    // Persistent Debug Bar
                    DebugBottomBar(engine = engine)
                }
            }
        }
    }
}