package org.balch.orpheus.djapp

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraphIos

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<DjAppGraphIos.Factory>().create()
    }
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

    DjApp(
        graph = graph,
        onTogglePlayback = {
            val controller = graph.playbackController
            if (controller.state.value == PlaybackState.Playing) controller.pause()
            else controller.play()
        },
    )
}
