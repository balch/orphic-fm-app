package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.config.AppConfig
import org.balch.orpheus.di.OrpheusGraphJvm
import org.balch.orpheus.ui.theme.OrpheusAssets
import org.balch.orpheus.util.GcMonitor
import org.jetbrains.compose.resources.painterResource

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    GcMonitor.install()

    application {
        val graph = remember { createGraphFactory<OrpheusGraphJvm.Factory>().create() }

        // Wire up logging to UI
        remember(graph) {
            KmLogging.addLogger(graph.consoleLogger)
        }
        // Eagerly initialize PlaybackController so its init {} subscribes to flows
        // at startup. Desktop has a real media session (macOS Now Playing + media
        // keys), so this is not Android-only.
        remember(graph) { graph.playbackController }
        // Eagerly initialize PulsarPlaybackBridge. It is the only caller of
        // setPulsarActive, so without it Pulsar never registers as an audio-activity
        // source and the Now Playing session drops while the beat machine is running.
        remember(graph) { graph.pulsarPlaybackBridge }
        // Eagerly initialize PulsarSongEnding so its init {} collectors observe
        // playback/arrangement state at startup. Without this touch the singleton
        // is never created and song-ending stays silently disabled.
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