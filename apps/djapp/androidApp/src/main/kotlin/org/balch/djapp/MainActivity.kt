package org.balch.djapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

        // When the review manager decides the user is engaged enough, launch the
        // Play in-app review flow. The Activity is the launch surface; Play decides
        // whether a card actually appears (and gives no result back).
        lifecycleScope.launch {
            graph.inAppReviewManager.reviewTriggers.collect {
                graph.inAppReviewManager.launchReview(this@MainActivity)
            }
        }

        setContent {
            DjApp(
                graph = graph,
                onTogglePlayback = {
                    val controller = graph.playbackController
                    if (controller.state.value == PlaybackState.Playing) controller.pause()
                    else controller.play()
                },
            ) {
                InAppUpdateHost(
                    manager = graph.inAppUpdateManager,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
