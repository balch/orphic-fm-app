package org.balch.orpheus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.balch.orpheus.core.playback.PlaybackState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge layout without androidx.activity's deprecated status/nav-bar
        // color setters (no-ops on Android 15+, and the source of the Play Console
        // edge-to-edge warnings). We hide the system bars entirely below, so the
        // bar-color scrim work enableEdgeToEdge() does would be pure waste here.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Full screen / Hide status bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Get the DI graph from Application (survives configuration changes)
        val graph = (application as OrpheusApplication).graph

        // Keep screen on only while actively playing. Cleared on pause/stop so
        // the OS screen timeout applies normally. NOTE: this is intentionally
        // narrower than isMediaSessionNeeded (which stays true across Paused,
        // e.g. Pulsar/Timer, so notification controls keep working) — the
        // screen should not stay awake just because the media session is alive.
        lifecycleScope.launch {
            graph.playbackController.state.collect { state ->
                if (state == PlaybackState.Playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            App(
                graph = graph,
                onFullyDrawn = {
                    // Report to benchmarking library that the app is fully drawn
                    // This is required for Macrobenchmark to detect startup completion
                    reportFullyDrawn()
                }
            )
        }
    }
}
