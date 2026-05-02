package org.balch.orpheus.core.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeMetadata(
    title: String = "T",
    subtitle: String = "S",
) : MetadataProducer {
    override val titleFlow = MutableStateFlow(title)
    override val subtitleFlow = MutableStateFlow(subtitle)
}

private class FakeOverlay(initial: String? = null) : OverlaySubtitleProducer {
    override val overlayFlow = MutableStateFlow(initial)
}

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

private fun build(
    metadata: MetadataProducer = FakeMetadata(),
    overlay: OverlaySubtitleProducer? = null,
    skip: SkipHandler? = null,
    playFromId: PlayFromMediaIdHandler? = null,
    muteCalls: MutableList<PlaybackState> = mutableListOf(),
): Triple<PlaybackController, MutableList<PlaybackState>, MediaSessionStateManager> {
    val scope = testScope()
    val ssm = MediaSessionStateManager(scope)
    val msm = MediaSessionManager()
    val plm = PlaybackLifecycleManager()
    val sink = MuteSink { state -> muteCalls.add(state) }
    val controller = PlaybackController(
        mediaSessionManager = msm,
        mediaSessionStateManager = ssm,
        playbackLifecycleManager = plm,
        muteSink = sink,
        metadataProducer = metadata,
        scope = scope,
        overlayProducer = overlay,
        skipHandler = skip,
        playFromMediaIdHandler = playFromId,
    )
    return Triple(controller, muteCalls, ssm)
}

class PlaybackControllerTest {

    @Test fun `initial state is Stopped`() = runTest {
        val (c, _, _) = build()
        assertEquals(PlaybackState.Stopped, c.state.value)
    }

    @Test fun `play transitions to Playing and applies mute sink`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val (c, _, _) = build(muteCalls = muteCalls)
        c.play()
        assertEquals(PlaybackState.Playing, c.state.value)
        assertEquals(listOf<PlaybackState>(PlaybackState.Playing), muteCalls)
    }

    @Test fun `pause from Playing transitions to Paused`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val (c, _, _) = build(muteCalls = muteCalls)
        c.play()
        c.pause()
        assertEquals(PlaybackState.Paused, c.state.value)
        assertEquals(listOf<PlaybackState>(PlaybackState.Playing, PlaybackState.Paused), muteCalls)
    }

    @Test fun `pause from Stopped is a no-op`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val (c, _, _) = build(muteCalls = muteCalls)
        c.pause()
        assertEquals(PlaybackState.Stopped, c.state.value)
        assertTrue(muteCalls.isEmpty())
    }

    @Test fun `stop from Playing transitions to Stopped`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val (c, _, _) = build(muteCalls = muteCalls)
        c.play()
        c.stop()
        assertEquals(PlaybackState.Stopped, c.state.value)
        assertEquals(
            listOf<PlaybackState>(PlaybackState.Playing, PlaybackState.Stopped),
            muteCalls,
        )
    }

    @Test fun `stop from Paused transitions to Stopped`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val (c, _, _) = build(muteCalls = muteCalls)
        c.play()
        c.pause()
        c.stop()
        assertEquals(PlaybackState.Stopped, c.state.value)
        assertEquals(
            listOf<PlaybackState>(
                PlaybackState.Playing,
                PlaybackState.Paused,
                PlaybackState.Stopped,
            ),
            muteCalls,
        )
    }

    @Test fun `skipHandler invoked on onSkipNext command`() = runTest {
        val skips = mutableListOf<SkipDirection>()
        val (c, _, _) = build(skip = SkipHandler { skips.add(it) })
        c.onSkipNext()
        c.onSkipPrevious()
        assertEquals(listOf<SkipDirection>(SkipDirection.NEXT, SkipDirection.PREVIOUS), skips)
    }

    @Test fun `skipHandler null does not crash on skip command`() = runTest {
        val (c, _, _) = build(skip = null)
        c.onSkipNext() // should be no-op, not NPE
        c.onSkipPrevious()
    }

    // Documents the architectural property that fixed the original
    // "force-kill required after backgrounding" bug. The old PulsarViewModel
    // had a `mediaPaused: Boolean` flag set by notification pause, only
    // cleared by notification play — so a sequence of (system pause, in-app
    // unmute, in-app mute) left the flag set forever, requiring force-kill
    // to recover. With state as the single source of truth, the flag is
    // gone and the equivalent sequence cleanly round-trips.
    @Test fun `system pause followed by system play round-trips cleanly`() = runTest {
        val (c, _, _) = build()
        c.play()           // Playing
        c.onPause()        // Paused (simulating notification pause)
        c.onPlay()         // Playing (simulating notification play)
        assertEquals(PlaybackState.Playing, c.state.value)
    }

    @Test fun `auto-start on session needed when Stopped`() = runTest {
        val (c, _, ssm) = build()
        assertEquals(PlaybackState.Stopped, c.state.value)
        // Triggering any source becoming active flips isMediaSessionNeeded → true.
        // The controller's init coroutine observes that and calls play() because
        // we're currently Stopped. With UnconfinedTestDispatcher backing the
        // controller's scope, the collector runs eagerly on this thread, so the
        // state transition is visible synchronously.
        ssm.setPulsarActive(true)
        assertEquals(PlaybackState.Playing, c.state.value)
    }

    // NOTE: The "overlay subtitle wins when present" behavior is exercised by the
    // metadata combine logic in PlaybackController (overlayFlow takes priority over
    // primarySubtitle in the combine block). Full assertion would require a spy on
    // MediaSessionManager.updateMetadata — the JVM actual no-ops when inactive,
    // making the call unobservable without major test infra rework. This smoke test
    // verifies that the code path compiles and runs without error. A future refactor
    // to inject a FakeMediaSessionManager would enable richer assertions.
    @Test fun `overlay subtitle wins smoke test`() = runTest {
        val overlayProducer = object : OverlaySubtitleProducer {
            override val overlayFlow = MutableStateFlow<String?>("OVERLAY")
        }
        val (c, _, _) = build(overlay = overlayProducer)
        // Activate the controller so metadata flows
        c.play()
        // Verify the controller reaches Playing state — the overlay logic ran without error.
        assertEquals(PlaybackState.Playing, c.state.value)
    }

    // NOTE: The "metadata updates are distinct-until-changed (no spam)" behavior
    // is enforced by the distinctUntilChanged() call in PlaybackController.init.
    // Asserting it directly would require counting MediaSessionManager.updateMetadata
    // calls, which the JVM actual doesn't support without test infra changes
    // (injecting a fake/spy). Skipped for now; the distinctUntilChanged() operator
    // is a stdlib primitive and is covered by its own library tests.
}
