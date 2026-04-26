package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.djapp.di.DjAppGraph

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")

    application {
        val graph = remember { createGraphFactory<DjAppGraph.Factory>().create() }

        Window(
            onCloseRequest = {
                // Fade out audio before exiting to avoid crackles/pops.
                // Set volume to 0, let the C++ smoother ramp down (~50ms),
                // then stop the engine and exit.
                graph.synthEngine.setMasterVolume(0f)
                Thread.sleep(80)
                graph.synthOrchestrator.stop()
                exitApplication()
            },
            title = "Orphic-DJ",
            state = rememberWindowState(width = 360.dp, height = 780.dp),
        ) {
            DjApp(graph)
        }
    }
}
