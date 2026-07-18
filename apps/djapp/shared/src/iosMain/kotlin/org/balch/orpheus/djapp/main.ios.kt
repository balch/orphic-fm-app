package org.balch.orpheus.djapp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.di.DjAppGraphIos
import platform.UIKit.UIApplication

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

    // Keep the screen awake only while actively playing, mirroring the Android
    // MainActivity's FLAG_KEEP_SCREEN_ON gating on PlaybackController.state.
    // idleTimerDisabled is a single global flag on UIApplication (unlike
    // Android's per-request WakeLock), so it's set directly here rather than
    // through WakeLockManager. Runs on Compose's main-confined effect dispatcher,
    // so no explicit dispatch_async to the main queue is needed.
    LaunchedEffect(Unit) {
        graph.playbackController.state.collect { state ->
            UIApplication.sharedApplication.idleTimerDisabled = (state == PlaybackState.Playing)
        }
    }

    DjApp(
        graph = graph,
        onTogglePlayback = {
            val controller = graph.playbackController
            if (controller.state.value == PlaybackState.Playing) controller.pause()
            else controller.play()
        },
    )
}
