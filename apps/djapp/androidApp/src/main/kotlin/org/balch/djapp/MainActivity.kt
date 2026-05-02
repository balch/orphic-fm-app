package org.balch.djapp

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
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.DjApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val graph = (application as DjAppApplication).graph

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
            DjApp(graph) {
                val controller = graph.playbackController
                if (controller.state.value == PlaybackState.Playing) controller.pause()
                else controller.play()
            }
        }
    }
}
