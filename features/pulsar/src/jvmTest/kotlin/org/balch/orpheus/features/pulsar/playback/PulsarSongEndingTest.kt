package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.MutablePrefs
import org.balch.orpheus.features.pulsar.MutableTimerFadeStatusProvider
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.makeMasterVolumeRamp
import org.balch.orpheus.features.pulsar.makeStubPlaybackController
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.EndStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PulsarSongEnding]. Drives a fake [PulsarFeature] +
 * a real [PlaybackController] and asserts trigger / final-section /
 * ramp / SongEnded behavior.
 *
 * The class is now `@SingleIn(AppScope::class) @Inject`. We construct it
 * directly here for unit testing — Metro-managed in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSongEndingTest {

    @Test
    fun `playing time accrues only while Playing`() = runTest {
        val harness = TestHarness(this)
        // Initial Stopped: no accrual.
        advanceTimeBy(2_000L)
        assertEquals(0L, harness.songEnding.playingMillisForTest)

        harness.playbackController.play()
        advanceTimeBy(3_000L)
        assertTrue(
            harness.songEnding.playingMillisForTest in 2_900..3_100,
            "after 3s of Playing, expected ~3000ms, got ${harness.songEnding.playingMillisForTest}",
        )

        harness.playbackController.pause()
        advanceTimeBy(5_000L)
        assertTrue(
            harness.songEnding.playingMillisForTest in 2_900..3_100,
            "Paused must not accrue, got ${harness.songEnding.playingMillisForTest}",
        )

        harness.playbackController.play()
        advanceTimeBy(2_000L)
        assertTrue(
            harness.songEnding.playingMillisForTest in 4_900..5_200,
            "after 2s more Playing, expected ~5000ms, got ${harness.songEnding.playingMillisForTest}",
        )
    }

    @Test
    fun `vibe change resets playing-time and triggered flag`() = runTest {
        val harness = TestHarness(this)
        harness.playbackController.play()
        advanceTimeBy(10_000L)
        assertTrue(
            harness.songEnding.playingMillisForTest >= 9_000L,
            "expected ~10s of accrued time, got ${harness.songEnding.playingMillisForTest}",
        )

        harness.feature.applyVibe(mkMinimalVibe("Other"))
        advanceUntilIdle()
        assertEquals(0L, harness.songEnding.playingMillisForTest)
        assertEquals(false, harness.songEnding.endingTriggeredForTest)
    }

    @Test
    fun `no trigger before minVibeSeconds`() = runTest {
        val harness = TestHarness(this)
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(60_000L)  // 60s < 150s default min
        harness.feature.arrangement.value = PulsarArrangementState(
            sectionIndex = 0, barsElapsed = 1, barsTotal = 8,
            soloActive = false, soloTrack = -1, soloMode = 0,
        )
        runCurrent()
        assertEquals(false, harness.songEnding.endingTriggeredForTest)
    }

    @Test
    fun `force trigger at maxVibeSeconds even with random=1`() = runTest {
        val harness = TestHarness(this, random = { _, _ -> 1.0f })
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(301_000L)  // > 300s max
        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        assertEquals(true, harness.songEnding.endingTriggeredForTest)
    }

    @Test
    fun `disabled preference suppresses all triggers`() = runTest {
        val harness = TestHarness(this, random = { _, _ -> 0f })
        harness.prefs.enabledFlow.value = false
        harness.playbackController.play()
        advanceTimeBy(310_000L)
        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.endingTriggeredForTest)
    }

    @Test
    fun `next section change after trigger becomes finalSectionIndex`() = runTest {
        val harness = TestHarness(this, random = { _, _ -> 0f })
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)

        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        assertTrue(harness.songEnding.endingTriggeredForTest)
        assertEquals(-1, harness.songEnding.finalSectionIndexForTest)

        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 8, false, -1, 0)
        runCurrent()
        assertEquals(5, harness.songEnding.finalSectionIndexForTest)
    }

    @Test
    fun `ramp scheduled in the bar containing the fade-start point`() = runTest {
        val harness = TestHarness(
            this,
            random = { _, _ -> 0f },
            // FADE_FAST = 0.25 bars → fade lives entirely inside the last bar.
            initialVibe = mkMinimalVibe("Test", endStyle = EndStyle.FADE_FAST),
        )
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)

        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()

        // bar 0 of 8 in the final section: barsRemaining=8, fade not yet.
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 8, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest)

        // bar 6 of 8: barsRemaining=2, ceil(0.25)=1 → still not yet.
        harness.feature.arrangement.value = PulsarArrangementState(5, 6, 8, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest)

        // bar 7 of 8 (last bar): barsRemaining=1 = ceil(0.25) → fade kicks in.
        harness.feature.arrangement.value = PulsarArrangementState(5, 7, 8, false, -1, 0)
        runCurrent()
        assertEquals(true, harness.songEnding.rampStartedForTest)
    }

    @Test
    fun `ramp deferred while timer is FADING`() = runTest {
        val harness = TestHarness(
            this,
            random = { _, _ -> 0f },
            initialVibe = mkMinimalVibe("Test", endStyle = EndStyle.FADE_FAST),
        )
        harness.timerFadeStatusProvider.fading = true
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)
        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 8, false, -1, 0)
        runCurrent()
        // bar 7 of 8 (last bar): would normally fire FADE_FAST=0.25; timer FADING blocks it.
        harness.feature.arrangement.value = PulsarArrangementState(5, 7, 8, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest)
    }

    @Test
    fun `FADE_SLOW fades only the last bar in a short outro`() = runTest {
        val harness = TestHarness(
            this,
            random = { _, _ -> 0f },
            // FADE_SLOW = 1.0 bar; cap = max(barsTotal-2, 1) = 2 → effective = 1.0.
            initialVibe = mkMinimalVibe("Test", endStyle = EndStyle.FADE_SLOW),
        )
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)
        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        // bars 0..2 of 4 in the final section: still full volume.
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 4, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest, "no fade at bar 0")

        harness.feature.arrangement.value = PulsarArrangementState(5, 1, 4, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest, "no fade at bar 1")

        harness.feature.arrangement.value = PulsarArrangementState(5, 2, 4, false, -1, 0)
        runCurrent()
        assertEquals(false, harness.songEnding.rampStartedForTest, "no fade at bar 2")

        // bar 3 of 4 (last bar): barsRemaining=1 = ceil(1.0) → fade kicks in.
        harness.feature.arrangement.value = PulsarArrangementState(5, 3, 4, false, -1, 0)
        runCurrent()
        assertEquals(true, harness.songEnding.rampStartedForTest, "fade at last bar")
    }

    @Test
    fun `SongEnded emitted when terminal outro section loops back to bar 0`() = runTest {
        val harness = TestHarness(this, random = { _, _ -> 0f })
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        // Trigger.
        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        // Move into final outro section (5).
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 4, false, -1, 0)
        runCurrent()
        // Tick through the outro.
        harness.feature.arrangement.value = PulsarArrangementState(5, 1, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(5, 2, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(5, 3, 4, false, -1, 0)
        runCurrent()
        // Outro loops back to bar 0 of itself (terminal section with no transitions).
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 4, false, -1, 0)
        runCurrent()

        assertTrue(
            collected.any { it is SongEndingEvent.SongEnded },
            "SongEnded must fire when the terminal outro loops back to bar 0",
        )
        job.cancel()
    }

    @Test
    fun `SongEnded emitted at boundary out of final section`() = runTest {
        val harness = TestHarness(this, random = { _, _ -> 0f })
        harness.prefs.enabledFlow.value = true
        harness.playbackController.play()
        advanceTimeBy(160_000L)

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        harness.feature.arrangement.value = PulsarArrangementState(0, 1, 8, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(5, 0, 8, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(5, 7, 8, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(0, 0, 8, false, -1, 0)
        runCurrent()

        assertTrue(collected.any { it is SongEndingEvent.OutroTriggered }, "OutroTriggered emitted")
        assertTrue(collected.any { it is SongEndingEvent.SongEnded }, "SongEnded emitted")
        job.cancel()
    }
}

// ─── Test harness ────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestHarness(
    testScope: TestScope,
    random: (Float, Float) -> Float = { _, _ -> 0.5f },
    initialVibe: org.balch.orpheus.features.pulsar.models.Vibe = mkMinimalVibe("Test"),
) {
    val feature = FakePulsarFeature(vibeList = listOf(initialVibe), initial = initialVibe)
    val prefs = MutablePrefs()
    val timerFadeStatusProvider = MutableTimerFadeStatusProvider()
    val synthController: SynthController = SynthController().apply {
        val ports = mutableMapOf<String, PortValue>()
        setDelegates(
            setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
            getter = { id -> ports["${id.uri}:${id.symbol}"] },
        )
    }
    val stubEngine = SongEndingStubSynthEngine()
    val ramp = makeMasterVolumeRamp(stubEngine)
    private val scope = makeAppCoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))
    val playbackController = makeStubPlaybackController(scope)

    val songEnding = PulsarSongEnding(
        pulsarFeature = feature,
        playbackController = playbackController,
        preferences = prefs,
        synthController = synthController,
        synthEngine = stubEngine,
        ramp = ramp,
        timerFadeStatusProvider = timerFadeStatusProvider,
        scope = scope,
    ).apply {
        nowMillis = { testScope.testScheduler.currentTime }
        this.random = random
    }
}
