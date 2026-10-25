package org.balch.djapp

import android.content.Intent
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
import org.balch.orpheus.djapp.startDjAudio
import org.balch.orpheus.djapp.largeScreenCanvasScale
import org.balch.orpheus.djapp.LocalCanvasScale
import org.balch.orpheus.djapp.LocalHinge
import org.balch.orpheus.djapp.rememberTabletopHinge
import org.balch.orpheus.djapp.share.withShareSubject

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerShare(intent)
    }

    // Skips history relaunches; the extra is removed after offering so a rotation
    // (same Intent object) finds nothing to offer again.
    private fun offerShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.type?.startsWith("text/") != true) return
        // CharSequence reads keep rich-text shares that getStringExtra would drop as null.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        (application as DjAppApplication).graph.sharedTextInbox.offer(withShareSubject(text, subject))
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

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

        // Not gated on savedInstanceState: a fresh SEND after process death carries one.
        offerShare(intent)

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
            // TVs and tablet-class screens in landscape widen the dp canvas so the
            // fixed-width dock panels fit (see largeScreenCanvasScale). It returns 1f on phones,
            // on folded cover displays and in portrait, so this is inert everywhere else.
            // fontScale passes through untouched, keeping the user's own text-size setting.
            // The fold is read first: tabletop switches the canvas scale off and picks the split.
            val hinge = rememberTabletopHinge(this@MainActivity)
            val canvasScale = largeScreenCanvasScale(hinge)
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides
                    Density(baseDensity.density * canvasScale, baseDensity.fontScale),
                LocalCanvasScale provides canvasScale,
                LocalHinge provides hinge,
            ) {
                DjApp(
                    graph = graph,
                    onTogglePlayback = {
                        val controller = graph.playbackController
                        if (controller.state.value == PlaybackState.Playing) controller.pause()
                        else controller.play()
                    },
                    startAudio = { graph.startDjAudio() },
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
