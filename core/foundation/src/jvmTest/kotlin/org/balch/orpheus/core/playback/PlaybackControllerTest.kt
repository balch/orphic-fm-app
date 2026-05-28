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

private data class Built(
    val controller: PlaybackController,
    val muteCalls: MutableList<PlaybackState>,
    val ssm: MediaSessionStateManager,
    val msm: MediaSessionManager,
)

private fun build(
    metadata: MetadataProducer = FakeMetadata(),
    overlay: OverlaySubtitleProducer? = null,
    skip: SkipHandler? = null,
    playFromId: PlayFromMediaIdHandler? = null,
    muteCalls: MutableList<PlaybackState> = mutableListOf(),
    focusGranted: Boolean = true,
): Built {
    val scope = testScope()
    val ssm = MediaSessionStateManager(scope)
    val msm = MediaSessionManager().apply { focusGrantOverride = focusGranted }
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
    return Built(controller, muteCalls, ssm, msm)
}

class PlaybackControllerTest {

    @Test fun `initial state is Stopped`() = runTest {
        val c = build().controller
        assertEquals(PlaybackState.Stopped, c.state.value)
    }

    @Test fun `play transitions to Playing and applies mute sink`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val c = build(muteCalls = muteCalls).controller
        c.play()
        assertEquals(PlaybackState.Playing, c.state.value)
        assertEquals(listOf<PlaybackState>(PlaybackState.Playing), muteCalls)
    }

    @Test fun `pause from Playing transitions to Paused`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val c = build(muteCalls = muteCalls).controller
        c.play()
        c.pause()
        assertEquals(PlaybackState.Paused, c.state.value)
        assertEquals(listOf<PlaybackState>(PlaybackState.Playing, PlaybackState.Paused), muteCalls)
    }

    @Test fun `pause from Stopped is a no-op`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val c = build(muteCalls = muteCalls).controller
        c.pause()
        assertEquals(PlaybackState.Stopped, c.state.value)
        assertTrue(muteCalls.isEmpty())
    }

    @Test fun `stop from Playing transitions to Stopped`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val c = build(muteCalls = muteCalls).controller
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
        val c = build(muteCalls = muteCalls).controller
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
        val c = build(skip = SkipHandler { skips.add(it) }).controller
        c.onSkipNext()
        c.onSkipPrevious()
        assertEquals(listOf<SkipDirection>(SkipDirection.NEXT, SkipDirection.PREVIOUS), skips)
    }

    @Test fun `skipHandler null does not crash on skip command`() = runTest {
        val c = build(skip = null).controller
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
        val c = build().controller
        c.play()           // Playing
        c.onPause()        // Paused (simulating notification pause)
        c.onPlay()         // Playing (simulating notification play)
        assertEquals(PlaybackState.Playing, c.state.value)
    }

    @Test fun `auto-start on session needed when Stopped`() = runTest {
        val (c, _, ssm) = build().let { Triple(it.controller, it.muteCalls, it.ssm) }
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
        val c = build(overlay = overlayProducer).controller
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

    // ── Audio-focus interaction ────────────────────────────────────────────

    // Before the fix, play() set state=Playing and unmuted the sink BEFORE
    // mediaSessionManager.activate() resolved audio focus — on a denied
    // request the engine bled audio with no foreground service backing it.
    @Test fun `play is a no-op when audio focus is denied`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(focusGranted = false, muteCalls = muteCalls)
        built.controller.play()
        assertEquals(PlaybackState.Stopped, built.controller.state.value)
        assertTrue(muteCalls.isEmpty(), "muteSink should not be applied on focus denial")
    }

    @Test fun `play proceeds when audio focus is granted`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(focusGranted = true, muteCalls = muteCalls)
        built.controller.play()
        assertEquals(PlaybackState.Playing, built.controller.state.value)
        assertEquals(listOf<PlaybackState>(PlaybackState.Playing), muteCalls)
    }

    // After a denied request, the user must be able to retry — verify state
    // remained clean (no Paused interloper) and a subsequent grant works.
    @Test fun `play retried after focus is regranted`() = runTest {
        val built = build(focusGranted = false)
        built.controller.play()
        assertEquals(PlaybackState.Stopped, built.controller.state.value)
        built.msm.focusGrantOverride = true
        built.controller.play()
        assertEquals(PlaybackState.Playing, built.controller.state.value)
    }

    // pause() must signal user-intent to the focus controller (clears the
    // pausedByTransient flag) so a later AUDIOFOCUS_GAIN does not ghost-resume
    // playback against the user's wishes.
    @Test fun `pause notifies user-paused even on no-op transitions`() = runTest {
        val built = build()
        // From Stopped — already not Playing, but the user may have just been
        // paused by a transient loss and is tapping pause to acknowledge.
        built.controller.pause()
        assertEquals(1, built.msm.userPausedCount)

        // From Playing — same signal.
        built.controller.play()
        val before = built.msm.userPausedCount
        built.controller.pause()
        assertEquals(before + 1, built.msm.userPausedCount)
    }

    @Test fun `stop notifies user-paused`() = runTest {
        val built = build()
        built.controller.play()
        val before = built.msm.userPausedCount
        built.controller.stop()
        assertTrue(
            built.msm.userPausedCount > before,
            "stop() must notify user-paused so the focus controller drops any pending auto-resume",
        )
    }

    // The transient-loss path bypasses the user-pause signal so that a
    // matching AUDIOFOCUS_GAIN can still auto-resume. Verify state changes
    // happen but notifyUserPaused is NOT called.
    @Test fun `onPauseFromFocusLoss pauses without notifying user-paused`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(muteCalls = muteCalls)
        built.controller.play()
        val before = built.msm.userPausedCount

        built.controller.onPauseFromFocusLoss()

        assertEquals(PlaybackState.Paused, built.controller.state.value)
        assertEquals(
            listOf<PlaybackState>(PlaybackState.Playing, PlaybackState.Paused),
            muteCalls,
        )
        assertEquals(
            before,
            built.msm.userPausedCount,
            "onPauseFromFocusLoss must NOT clear pausedByTransient — auto-resume on GAIN depends on it",
        )
    }

    @Test fun `onPauseFromFocusLoss is a no-op when not Playing`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(muteCalls = muteCalls)
        // From Stopped
        built.controller.onPauseFromFocusLoss()
        assertEquals(PlaybackState.Stopped, built.controller.state.value)
        assertTrue(muteCalls.isEmpty())
        assertEquals(0, built.msm.userPausedCount)
    }
}
