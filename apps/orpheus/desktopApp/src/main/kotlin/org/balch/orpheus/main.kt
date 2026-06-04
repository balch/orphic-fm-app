package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.di.OrpheusGraph
import org.balch.orpheus.ui.theme.OrpheusAssets
import org.balch.orpheus.util.GcMonitor
import org.jetbrains.compose.resources.painterResource

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    GcMonitor.install()

    application {
        val graph = remember { createGraphFactory<OrpheusGraph.Factory>().create() }

        // Wire up logging to UI
        remember(graph) {
            KmLogging.addLogger(graph.consoleLogger)
        }
        // Eagerly initialize PulsarSongEnding so its init {} collectors observe
        // playback/arrangement state at startup. Without this touch the singleton
        // is never created and song-ending stays silently disabled. Same pattern
        // as PulsarPlaybackBridge in the DJ app.
        remember(graph) { graph.pulsarSongEnding }
        // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
        // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
        remember(graph) { graph.pulsarSongAdvancer }

        Window(
            onCloseRequest = ::exitApplication,
            title = AppConfig.APP_DISPLAY_NAME,
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            icon = painterResource(OrpheusAssets.icon),
        ) {
            App(graph)
        }
    }
}