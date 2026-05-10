package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraph

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")

    application {
        val graph = remember { createGraphFactory<DjAppGraph.Factory>().create() }
        // Eagerly initialize PulsarPlaybackBridge so its init {} subscribes to
        // PlaybackController.state at startup. Without this touch the singleton
        // is never created and the PULSAR_PLAYING port stays at 0.
        remember { graph.pulsarPlaybackBridge }
        // Eagerly initialize PulsarSongEnding so its init {} collectors observe
        // playback/arrangement state at startup. Decoupled from PulsarViewModel
        // for the same reason as PulsarPlaybackBridge (DI cycle prevention).
        remember { graph.pulsarSongEnding }
        // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
        // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
        remember { graph.pulsarSongAdvancer }

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
            title = "Orphic DJ",
            state = rememberWindowState(width = 360.dp, height = 780.dp),
        ) {
            DjApp(graph) {
                val controller = graph.playbackController
                if (controller.state.value == PlaybackState.Playing) controller.pause()
                else controller.play()
            }
        }
    }
}
