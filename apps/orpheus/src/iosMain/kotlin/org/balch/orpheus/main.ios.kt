package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraph

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<OrpheusGraph.Factory>().create().also { g ->
            KmLogging.addLogger(g.consoleLogger)
        }
    }
    // Eagerly initialize PulsarSongEnding so its init {} collectors observe
    // playback/arrangement state at startup. Without this touch the singleton
    // is never created and song-ending stays silently disabled.
    remember { graph.pulsarSongEnding }
    // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
    // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
    remember { graph.pulsarSongAdvancer }
    App(graph)
}
