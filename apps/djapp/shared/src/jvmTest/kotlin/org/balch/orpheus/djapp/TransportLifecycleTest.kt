package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A playing ring lets go of the scope feed, and the chrome of the music pulse, while the app is
 * stopped: a backgrounded iOS app, a minimised desktop window. Android's own visibility gate
 * never reaches those, and the audio plays on. On a lifecycle the test moves by hand.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*TransportLifecycleTest*' --rerun
 */
class TransportLifecycleTest {
    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** Playing a vibe with progress, so its ring holds the feed; counts the pulse holds it hands out. */
    private class Playing : PulsarFeature by PulsarViewModel.previewFeature() {
        var pulseHolds = 0
            private set
        override val stateFlow: StateFlow<PulsarUiState> =
            MutableStateFlow(PulsarViewModel.previewFeature().stateFlow.value.copy(globalPaused = false))
        override val vibeNavFlow: StateFlow<VibeNavState> =
            MutableStateFlow(VibeNavState("Drift", "Dog House", "Stay Asleep", progress = 0.62f))

        override fun holdMusicPulse(): DisposableHandle {
            pulseHolds++
            return DisposableHandle { pulseHolds-- }
        }
    }

    /** Composes [content] under a hand-moved lifecycle and a feed recording its enables, then walks it through a stop and a start. */
    private fun assertLetsGoWhileStopped(what: String, widthDp: Int, heightDp: Int, content: @Composable (PulsarFeature) -> Unit) {
        val lifecycle = TestLifecycle()
        val enables = mutableListOf<Boolean>()
        val feed = ScopeFeed(TestScopeFrame()) { enables += it }
        val pulsar = Playing()
        val scene = ImageComposeScene(widthDp, heightDp, Density(1f)) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalScopeFeed provides feed,
                LocalTelevisionHardware provides false,
            ) {
                OrpheusTheme { content(pulsar) }
            }
        }
        var now = 1_000L
        fun frame() {
            now += 16
            scene.render(now * 1_000_000).close()
        }
        try {
            repeat(3) { frame() }
            // collectAsStateWithLifecycle registers with the lifecycle from the main dispatcher, and the
            // registry is unguarded: let that finish before this thread moves it.
            runBlocking(Dispatchers.Main) {}
            assertEquals(listOf(true), enables, "sanity: the playing $what never enabled the scope")
            assertEquals(1, pulsar.pulseHolds, "sanity: the $what never held the pulse")

            // Backgrounded (iOS) or minimised (desktop): the scene stops, and so do its polls.
            lifecycle.registry.currentState = Lifecycle.State.CREATED
            repeat(3) { frame() }
            assertEquals(listOf(true, false), enables, "the stopped $what kept the scope enabled")
            assertEquals(0, pulsar.pulseHolds, "the stopped $what kept holding the pulse")

            lifecycle.registry.currentState = Lifecycle.State.RESUMED
            repeat(3) { frame() }
            assertEquals(listOf(true, false, true), enables, "the $what did not take the scope back on its return")
            assertEquals(1, pulsar.pulseHolds, "the $what did not hold the pulse again on its return")

            // Stopped again, then torn down while stopped: what the stop let go is not let go twice.
            lifecycle.registry.currentState = Lifecycle.State.CREATED
            repeat(3) { frame() }
        } finally {
            scene.close()
        }
        assertEquals(listOf(true, false, true, false), enables, "the $what's scene closed with the scope enabled, or disabled it twice")
        assertEquals(0, pulsar.pulseHolds, "the $what's scene closed holding the pulse, or let it go twice")
    }

    @Test
    fun thePhoneBarLetsGoWhileStopped() = assertLetsGoWhileStopped("phone bar", 360, 780) { pulsar ->
        DjAppNavScaffold(
            isSelected = { it == DjTab }, onItemClick = {}, layout = DjLayout.Portrait, pulsarFeature = pulsar,
            timerFeature = TimerViewModel.previewFeature(), onTogglePlayback = {}, modifier = Modifier.fillMaxSize(),
        ) { Box(Modifier.fillMaxSize()) }
    }

    @Test
    fun theDockDomeLetsGoWhileStopped() = assertLetsGoWhileStopped("dock dome", 1280, 200) { pulsar ->
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()), isDocked = { false }, onToggle = {},
            timerFeature = TimerViewModel.previewFeature(), pulsarFeature = pulsar, onTogglePlayback = {},
        )
    }
}
