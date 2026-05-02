package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraph

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<DjAppGraph.Factory>().create()
    }
    // Eagerly initialize PulsarPlaybackBridge so its init {} subscribes to
    // PlaybackController.state at startup. Without this touch the singleton
    // is never created and the PULSAR_PLAYING port stays at 0.
    remember { graph.pulsarPlaybackBridge }

    DjApp(graph) {
        val controller = graph.playbackController
        if (controller.state.value == PlaybackState.Playing) controller.pause()
        else controller.play()
    }
}
