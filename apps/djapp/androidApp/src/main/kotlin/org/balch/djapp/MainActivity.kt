package org.balch.djapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.djapp.DjApp
import org.balch.orpheus.djapp.tvDensityScale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge layout without androidx.activity's deprecated status/nav-bar
        // color setters (no-ops on Android 15+, and the source of the Play Console
        // edge-to-edge warnings). We hide the system bars entirely below, so the
        // bar-color scrim work enableEdgeToEdge() does would be pure waste here.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val graph = (application as DjAppApplication).graph

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

        // When the review manager decides the user is engaged enough, launch the
        // Play in-app review flow. The Activity is the launch surface; Play decides
        // whether a card actually appears (and gives no result back).
        lifecycleScope.launch {
            graph.inAppReviewManager.reviewTriggers.collect {
                graph.inAppReviewManager.launchReview(this@MainActivity)
            }
        }

        setContent {
            // TV widens the dp canvas so the fixed-width dock panels fit (see tvDensityScale).
            // tvScale is 1f off television hardware, so this is inert on phones and tablets.
            // fontScale passes through untouched, keeping the user's own text-size setting.
            val tvScale = tvDensityScale()
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density * tvScale, baseDensity.fontScale),
            ) {
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
}
