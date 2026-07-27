package org.balch.orpheus

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraphIos

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        createGraphFactory<OrpheusGraphIos.Factory>().create().also { g ->
            KmLogging.addLogger(g.consoleLogger)
        }
    }
    // Eagerly initialize PlaybackController so its init {} subscribes to flows at
    // startup. iOS has a real media session (MPNowPlayingInfoCenter + remote command
    // center), so this is not Android-only.
    remember { graph.playbackController }
    // Eagerly initialize PulsarPlaybackBridge. It is the only caller of
    // setPulsarActive, so without it Pulsar never registers as an audio-activity
    // source and the lock-screen transport drops while the beat machine is running.
    remember { graph.pulsarPlaybackBridge }
    // Eagerly initialize PulsarSongEnding so its init {} collectors observe
    // playback/arrangement state at startup. Without this touch the singleton
    // is never created and song-ending stays silently disabled.
    remember { graph.pulsarSongEnding }
    // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
    // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
    remember { graph.pulsarSongAdvancer }
    App(graph)
}
