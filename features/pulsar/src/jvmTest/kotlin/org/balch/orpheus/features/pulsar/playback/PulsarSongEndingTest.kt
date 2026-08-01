package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.makeSongEnding
import org.balch.orpheus.features.pulsar.makeStubPlaybackController
import org.balch.orpheus.features.pulsar.mkMinimalVibe
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

    // ─── Regression: arming while already IN the outro section ──────────────────
    // Repro for the "Tremolo Tide keeps repeating breakdown" bug. When a vibe's
    // outroIndex points at a section that is also reachable during normal play
    // (e.g. breakdown), the C++ engine pins current_section to outroIndex once
    // armed and never leaves it. If the user arms the outro while the song is
    // ALREADY in that section, the section index never changes, so the
    // change-based finalSectionIndex capture never fires — and SongEnded never
    // emits, so the section loops forever with no ending indicator.

    @Test
    fun `arming outro while already in outro section captures finalSectionIndex immediately`() = runTest {
        val vibe = mkVibeWithOutro("Tide", sectionCount = 2, outroIndex = 1)
        val harness = TestHarness(this, initialVibe = vibe)
        harness.playbackController.play()

        // Song is currently in the outro section (1), part-way through.
        harness.feature.arrangement.value = PulsarArrangementState(1, 2, 6, false, -1, 0)
        runCurrent()

        // User presses ENDING while already in section 1.
        harness.songEnding.armOutro()
        runCurrent()

        assertEquals(
            1, harness.songEnding.finalSectionIndexForTest,
            "outroIndex must be captured immediately — no section-index change will ever occur",
        )
    }

    @Test
    fun `SongEnded fires when outro armed while already in outroIndex section then loops`() = runTest {
        val vibe = mkVibeWithOutro("Tide", sectionCount = 2, outroIndex = 1)
        val harness = TestHarness(this, initialVibe = vibe)
        harness.playbackController.play()

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        // In the outro section (1), mid-way.
        harness.feature.arrangement.value = PulsarArrangementState(1, 2, 6, false, -1, 0)
        runCurrent()
        // Arm while in section 1.
        harness.songEnding.armOutro()
        runCurrent()
        // Section 1 keeps playing (index never changes — engine pins it here).
        harness.feature.arrangement.value = PulsarArrangementState(1, 4, 6, false, -1, 0)
        runCurrent()
        // Section 1 loops back to bar 0 of itself.
        harness.feature.arrangement.value = PulsarArrangementState(1, 0, 6, false, -1, 0)
        runCurrent()

        assertTrue(
            collected.any { it is SongEndingEvent.SongEnded },
            "SongEnded must fire when the armed outro section loops, even if armed while already in it",
        )
        job.cancel()
    }

    @Test
    fun `no premature SongEnded on entry into outro section when armed elsewhere`() = runTest {
        val vibe = mkVibeWithOutro("Tide", sectionCount = 2, outroIndex = 1)
        val harness = TestHarness(this, initialVibe = vibe)
        harness.playbackController.play()

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        // In section 0, several bars in.
        harness.feature.arrangement.value = PulsarArrangementState(0, 5, 8, false, -1, 0)
        runCurrent()
        // Arm while NOT in the outro section.
        harness.songEnding.armOutro()
        runCurrent()
        // Engine routes into the outro section (1): barsElapsed resets to 0. This
        // must NOT be mistaken for a loop of the final section.
        harness.feature.arrangement.value = PulsarArrangementState(1, 0, 6, false, -1, 0)
        runCurrent()

        assertTrue(
            collected.none { it is SongEndingEvent.SongEnded },
            "entering the final section is not the same as the final section looping",
        )
        job.cancel()
    }

    // ─── Regression: terminal outro section reached during NORMAL play ──────────
    // Repro for the "stuck in the last section, never ends" bug (TechnoWobble's
    // `drift`). When a vibe's outroIndex is a TERMINAL section (transitions =
    // emptyList()) that is ALSO reachable via normal Markov edges, the C++ engine
    // self-loops it forever (select_next_section returns `current` for a
    // zero-transition section) once the walk lands there. If this happens before
    // any timed/manual arm — and especially with song-ending disabled — the old
    // detection stayed asleep (gated on _endingTriggered) and the song droned in
    // the outro forever. Reaching a structurally-terminal outro section must end
    // the song regardless of the auto-end preference.

    @Test
    fun `SongEnded fires when arrangement walks into terminal outro section unarmed`() = runTest {
        // 3 sections; section 2 is the terminal outro (default transitions =
        // emptyList()), mirroring TechnoWobble's drift.
        val vibe = mkVibeWithOutro("Tw", sectionCount = 3, outroIndex = 2)
        val harness = TestHarness(this, initialVibe = vibe)
        // PLAYS mode: timed auto-end disabled, and we never call armOutro().
        // Only reaching the terminal outro section can end the song.
        harness.prefs.enabledFlow.value = false
        harness.playbackController.play()
        // Past the minimum song length (default 150s) so the structural
        // terminal-outro end is allowed to fire (it is gated on minVibeSeconds).
        advanceTimeBy(160_000L)

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        // Normal Markov play rolls into the terminal outro section (2). Unarmed.
        harness.feature.arrangement.value = PulsarArrangementState(2, 0, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(2, 1, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(2, 2, 4, false, -1, 0)
        runCurrent()
        // Engine self-loops the terminal section back to bar 0.
        harness.feature.arrangement.value = PulsarArrangementState(2, 0, 4, false, -1, 0)
        runCurrent()

        assertTrue(
            collected.any { it is SongEndingEvent.SongEnded },
            "a terminal outro section reached during normal play must end the song, " +
                "even when unarmed and auto-end is disabled",
        )
        job.cancel()
    }

    @Test
    fun `terminal outro section reached before minVibeSeconds does not auto-arm`() = runTest {
        val vibe = mkVibeWithOutro("Tw", sectionCount = 3, outroIndex = 2)
        val harness = TestHarness(this, initialVibe = vibe)
        harness.prefs.enabledFlow.value = false
        harness.playbackController.play()

        val collected = mutableListOf<SongEndingEvent>()
        val job = launch { harness.songEnding.songEndingEvents.collect { collected += it } }
        runCurrent()

        // Only 30s in — well under the 150s minimum. Walking into the terminal
        // outro section here must NOT end the song: the minimum length wins, so
        // the auto-arm holds off until minVibeSeconds.
        advanceTimeBy(30_000L)
        harness.feature.arrangement.value = PulsarArrangementState(2, 0, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(2, 1, 4, false, -1, 0)
        runCurrent()
        harness.feature.arrangement.value = PulsarArrangementState(2, 0, 4, false, -1, 0)
        runCurrent()

        assertEquals(
            false, harness.songEnding.endingTriggeredForTest,
            "terminal outro reached before minVibeSeconds must not arm",
        )
        assertTrue(
            collected.none { it is SongEndingEvent.SongEnded },
            "song must not end before its minimum length",
        )
        job.cancel()
    }

    // ─── Invariant: RANDOM never resolves to a non-style ────────────────────────
    // "PLAYS" is NOT a TransitionStyle — it's only the pill's label when song-
    // ending is disabled. The RANDOM resolver picks from the safe styles, which
    // excludes RANDOM itself. This locks that invariant so a future enum/pool
    // change can't let RANDOM resolve to RANDOM (or any unsafe value).

    @Test
    fun `RANDOM transition resolves to a safe concrete style and never offers RANDOM`() = runTest {
        val harness = TestHarness(this, initialVibe = mkMinimalVibe("Init"))
        var offeredPool: List<TransitionStyle> = emptyList()
        harness.songEnding.randomPicker = { pool -> offeredPool = pool; pool.first() }

        // Apply a vibe whose transition-out is RANDOM (empty pool => safe-styles fallback).
        harness.feature.applyVibe(
            mkMinimalVibe("Rnd", transitionOut = TransitionSpec(TransitionStyle.RANDOM)),
        )
        advanceUntilIdle()

        assertTrue(offeredPool.isNotEmpty(), "random candidate pool must not be empty")
        assertTrue(
            TransitionStyle.RANDOM !in offeredPool,
            "RANDOM must never be a candidate for itself (no recursion, no PLAYS)",
        )
        assertTrue(offeredPool.all { it.isSafe }, "every candidate must be a safe, concrete style")
        assertEquals(
            TransitionStyle.entries.filter { it.isSafe }.size,
            offeredPool.size,
            "empty randomPool must fall back to exactly the safe-style set",
        )
        assertTrue(
            harness.songEnding.resolvedTransitionStyle.value != TransitionStyle.RANDOM,
            "resolved style must be concrete, never RANDOM",
        )
    }
}

/**
 * Build a [Vibe] whose arrangement names a dedicated [outroIndex] across
 * [sectionCount] sections. Mirrors how production vibes set
 * `outroIndex = sectionList.lastIndex`.
 */
private fun mkVibeWithOutro(
    name: String,
    sectionCount: Int,
    outroIndex: Int,
): org.balch.orpheus.features.pulsar.models.Vibe {
    val base = mkMinimalVibe(name)
    val arr = requireNotNull(base.arrangement)
    return base.copy(
        arrangement = arr.copy(
            sections = List(sectionCount) {
                org.balch.orpheus.features.pulsar.models.Section(name = "s$it")
            },
            outroIndex = outroIndex,
        ),
    )
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
    val synthController: SynthController = SynthController().apply {
        val ports = mutableMapOf<String, PortValue>()
        setDelegates(
            setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
            getter = { id -> ports["${id.uri}:${id.symbol}"] },
        )
    }
    private val scope = makeAppCoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler))
    val playbackController = makeStubPlaybackController(scope)

    val songEnding = makeSongEnding(
        feature = feature,
        playbackController = playbackController,
        preferences = prefs,
        synthController = synthController,
        scope = scope,
    ).apply {
        nowMillis = { testScope.testScheduler.currentTime }
        this.random = random
    }
}
