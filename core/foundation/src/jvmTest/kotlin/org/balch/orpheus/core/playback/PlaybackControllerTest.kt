package org.balch.orpheus.core.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.AudioRouteMonitor
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.engagement.DefaultEngagementTracker
import org.balch.orpheus.core.engagement.EngagementAction
import org.balch.orpheus.core.lifecycle.PlaybackLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private class FakeRouteMonitor : AudioRouteMonitor {
    private val _routeLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val audioRouteLostFlow: SharedFlow<Unit> = _routeLost.asSharedFlow()
    fun emitRouteLost() {
        check(_routeLost.tryEmit(Unit)) { "route-lost emission dropped" }
    }
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
    val tracker: DefaultEngagementTracker,
)

private fun build(
    metadata: MetadataProducer = FakeMetadata(),
    overlay: OverlaySubtitleProducer? = null,
    skip: SkipHandler? = null,
    playFromId: PlayFromMediaIdHandler? = null,
    muteCalls: MutableList<PlaybackState> = mutableListOf(),
    focusGranted: Boolean = true,
    routeMonitor: AudioRouteMonitor? = null,
): Built {
    val scope = testScope()
    val ssm = MediaSessionStateManager(scope)
    val msm = MediaSessionManager().apply { focusGrantOverride = focusGranted }
    val plm = PlaybackLifecycleManager()
    val sink = MuteSink { state -> muteCalls.add(state) }
    val tracker = DefaultEngagementTracker()
    val controller = PlaybackController(
        mediaSessionManager = msm,
        mediaSessionStateManager = ssm,
        playbackLifecycleManager = plm,
        muteSink = sink,
        engagementTracker = tracker,
        metadataProducer = metadata,
        scope = scope,
        overlayProducer = overlay,
        skipHandler = skip,
        playFromMediaIdHandler = playFromId,
        audioRouteMonitor = routeMonitor,
    )
    return Built(controller, muteCalls, ssm, msm, tracker)
}

class PlaybackControllerTest {

    @Test fun `initial state is Stopped`() = runTest {
        val c = build().controller
        assertEquals(PlaybackState.Stopped, c.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `pause records a PAUSE engagement on a real play to pause transition`() =
        runTest(UnconfinedTestDispatcher()) {
            val built = build()
            val seen = mutableListOf<EngagementAction>()
            val job = launch { built.tracker.events.collect { seen += it } }

            built.controller.play()   // -> Playing (focus granted by default)
            built.controller.pause()  // real transition -> records PAUSE

            assertEquals(listOf(EngagementAction.PAUSE), seen)
            job.cancel()
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

    // A bare Android Auto / MediaBrowser browse-bind (setAutoBrowserActive) flips
    // isMediaSessionNeeded → true, but the user is only browsing the library — it
    // must NOT auto-start audio. (Only the AUTO_BROWSER source is excluded; other
    // sources like Pulsar/Timer still auto-start — see the test above.)
    @Test fun `auto-browser bind keeps session active without auto-starting playback`() = runTest {
        val (c, _, ssm) = build().let { Triple(it.controller, it.muteCalls, it.ssm) }
        assertEquals(PlaybackState.Stopped, c.state.value)
        ssm.setAutoBrowserActive(true)
        // The session IS kept active for browsing (isMediaSessionNeeded flips
        // true) — that is the whole point of the AUTO_BROWSER source...
        assertTrue(ssm.isMediaSessionNeeded.value, "auto-browser bind must keep the session needed")
        // ...but audio must NOT auto-start (unlike Pulsar/Timer sources).
        assertEquals(PlaybackState.Stopped, c.state.value)
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

    // NOTE: The "metadata updates don't spam" behavior now relies on StateFlow's
    // built-in dropping of equal-by-equals emissions (the metadataSnapshotFlow is
    // a StateFlow; there is no longer a distinctUntilChanged() operator). Asserting
    // it directly would require counting MediaSessionManager.updateMetadata calls,
    // which the JVM actual doesn't support without injecting a fake/spy. Skipped
    // for now.

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
    // happen, notifyUserPaused is NOT called, and the method reports back that
    // it actually interrupted live playback (true) — the signal the Android
    // AudioFocusController uses to arm pausedByTransient.
    @Test fun `onPauseFromFocusLoss pauses from Playing and reports interruption`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(muteCalls = muteCalls)
        built.controller.play()
        val before = built.msm.userPausedCount

        val interrupted = built.controller.onPauseFromFocusLoss()

        assertTrue(
            interrupted,
            "onPauseFromFocusLoss must return true when it paused live playback so GAIN auto-resumes",
        )
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

    @Test fun `onPauseFromFocusLoss is a no-op and reports no interruption when not Playing`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(muteCalls = muteCalls)
        // From Stopped
        val interrupted = built.controller.onPauseFromFocusLoss()
        assertFalse(
            interrupted,
            "onPauseFromFocusLoss must return false when nothing was playing so GAIN does NOT auto-resume",
        )
        assertEquals(PlaybackState.Stopped, built.controller.state.value)
        assertTrue(muteCalls.isEmpty())
        assertEquals(0, built.msm.userPausedCount)
    }

    // ── Audio route loss (output device vanished) ──────────────────────────

    // Apple convention: when the active output device disappears (Bluetooth
    // speaker powered off), pause instead of continuing through the built-in
    // speaker. The pause is STICKY like a user pause — notifyUserPaused must
    // fire so no later focus-gain/route event auto-resumes playback.
    @Test fun `route lost while Playing auto-pauses sticky`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val monitor = FakeRouteMonitor()
        val built = build(muteCalls = muteCalls, routeMonitor = monitor)
        built.controller.play()
        val userPausedBefore = built.msm.userPausedCount

        monitor.emitRouteLost()

        assertEquals(PlaybackState.Paused, built.controller.state.value)
        assertEquals(
            listOf<PlaybackState>(PlaybackState.Playing, PlaybackState.Paused),
            muteCalls,
        )
        assertEquals(
            userPausedBefore + 1,
            built.msm.userPausedCount,
            "route-loss pause must be sticky (user-paused) so nothing auto-resumes",
        )
    }

    @Test fun `route lost while not Playing leaves state untouched`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val monitor = FakeRouteMonitor()
        val built = build(muteCalls = muteCalls, routeMonitor = monitor)

        monitor.emitRouteLost()

        assertEquals(PlaybackState.Stopped, built.controller.state.value)
        assertTrue(muteCalls.isEmpty())
    }

    // Regression for the [user-pause -> transient-loss -> gain] auto-resume bug:
    // after the user pauses (e.g. widget Pause) we still hold audio focus, so a
    // later transient loss (another app's autoplay video) arrives while Paused.
    // onPauseFromFocusLoss must report false so the AudioFocusController does NOT
    // arm pausedByTransient — otherwise the matching AUDIOFOCUS_GAIN resumes
    // playback against the user's intent.
    @Test fun `onPauseFromFocusLoss reports no interruption when already user-paused`() = runTest {
        val muteCalls = mutableListOf<PlaybackState>()
        val built = build(muteCalls = muteCalls)
        built.controller.play()
        built.controller.pause() // user pauses (widget), still holding focus
        muteCalls.clear()

        val interrupted = built.controller.onPauseFromFocusLoss()

        assertFalse(
            interrupted,
            "a transient loss while already user-paused must NOT arm auto-resume",
        )
        assertEquals(PlaybackState.Paused, built.controller.state.value)
        assertTrue(
            muteCalls.isEmpty(),
            "no mute transition should occur — we were already Paused",
        )
    }
}
