package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.MutablePrefs
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.makeStubPlaybackController
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration test for the song-ending pipeline. Wires a real
 * [PulsarSongEnding] and [PulsarSongAdvancer] against a fake [PulsarFeature]
 * + a stub [PlaybackController], then drives the arrangement state machine
 * through the happy path:
 *
 * 1. Playback enters Playing and accrues > minVibeSeconds of playing time.
 * 2. A bar tick in section 0 fires the trigger (random=0, p > 0).
 * 3. A section change to section 5 captures it as the final section.
 * 4. The last bar of the final section, then a section change back to 0,
 *    emits SongEnded.
 * 5. The advancer sees SongEnded and applyVibes the next vibe in the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSongEndingIntegrationTest {

    @Test
    fun `full pipeline triggers outro and auto-advances to next vibe`() = runTest {
        val vibes = listOf(mkMinimalVibe("First"), mkMinimalVibe("Second"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val prefs = MutablePrefs().apply { enabledFlow.value = true }
        val engine = SongEndingStubSynthEngine()
        val synthController = SynthController().apply {
            val ports = mutableMapOf<String, PortValue>()
            setDelegates(
                setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
                getter = { id -> ports["${id.uri}:${id.symbol}"] },
            )
        }
        val scope = makeAppCoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playbackController = makeStubPlaybackController(scope)

        val songEnding = PulsarSongEnding(
            pulsarFeatureProvider = { feature },
            playbackController = playbackController,
            preferences = prefs,
            transitionPreferences = StubTransitionPreferences(),
            synthController = synthController,
            scope = scope,
        ).apply {
            nowMillis = { testScheduler.currentTime }
            random = { _, _ -> 0f }
        }
        // Inline runner that just invokes applyNext (CUT-style) so the integration
        // test doesn't have to wait through fade/gap timelines. Per-style timeline
        // behavior is covered by `PulsarSongAdvancerIntegrationTest`; here we only
        // need to confirm SongEnded reaches the advancer and the next vibe applies.
        val runner = object : PulsarTransitionRunner {
            override val activeStyle: StateFlow<TransitionStyle?> =
                MutableStateFlow(null)

            override suspend fun runTransition(
                spec: TransitionSpec,
                applyNext: suspend () -> Unit,
            ) {
                applyNext()
            }
        }
        val advancer = PulsarSongAdvancer(
            feature, songEnding, StubTransitionPreferences(), runner, scope,
        )

        val collected = mutableListOf<SongEndingEvent>()
        val collectorJob = launch { songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        playbackController.play()
        advanceTimeBy(160_000L)

        // Tick a bar in section 0 → triggers.
        feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        advanceUntilIdle()
        // C++ routes us to final section 5.
        feature.arrangement.value = PulsarArrangementState(5, 0, 8, false, -1, 0)
        advanceUntilIdle()
        // Last bar of final section.
        feature.arrangement.value = PulsarArrangementState(5, 7, 8, false, -1, 0)
        advanceUntilIdle()
        // Boundary out — SongEnded fires AND advancer flips vibe to "Second";
        // the vibe-change in turn resets PulsarSongEnding's per-song state via
        // its vibeFlow collector.
        feature.arrangement.value = PulsarArrangementState(0, 0, 8, false, -1, 0)
        advanceUntilIdle()

        assertTrue(collected.any { it is SongEndingEvent.OutroTriggered }, "OutroTriggered emitted")
        assertTrue(collected.any { it is SongEndingEvent.SongEnded }, "SongEnded emitted")
        assertEquals("Second", feature.vibeFlow.value.name, "advancer flipped to next vibe")
        // After the advance, PulsarSongEnding's vibe-change collector resets
        // per-song state for the new vibe — endingTriggered must be false again.
        assertEquals(
            false,
            songEnding.endingTriggeredForTest,
            "song-ending state reset for new vibe"
        )
        assertTrue(advancer.enabled)

        collectorJob.cancel()
    }
}
