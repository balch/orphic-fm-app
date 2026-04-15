package org.balch.orpheus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge to edge
        enableEdgeToEdge()

        // Full screen / Hide status bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Get the DI graph from Application (survives configuration changes)
        val graph = (application as OrpheusApplication).graph

        // Keep screen on only while an audio source is active (timer, Pulsar, etc.).
        // When all sources stop (e.g. sleep timer finishes), the flag is cleared
        // so the device can sleep.
        lifecycleScope.launch {
            graph.mediaSessionStateManager.isMediaSessionNeeded.collect { needed ->
                if (needed) {
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
