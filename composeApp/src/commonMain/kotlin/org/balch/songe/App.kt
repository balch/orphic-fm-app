package org.balch.songe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.di.SongeGraph
import org.balch.songe.features.debug.DebugBottomBar
import org.balch.songe.features.navigation.SongeNavigation
import org.balch.songe.ui.theme.SongeTheme
import org.balch.songe.ui.widgets.VizBackground

@Composable
fun App(engine: SongeEngine, graph: SongeGraph) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        SongeTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                VizBackground()

                Column(modifier = Modifier.fillMaxSize()) {
                    // Main Content
                    Box(modifier = Modifier.weight(1f)) {
                        SongeNavigation(orchestrator = graph.synthOrchestrator)
                    }

                    // Persistent Debug Bar
                    DebugBottomBar(engine = engine)
                }
            }
        }
    }
}