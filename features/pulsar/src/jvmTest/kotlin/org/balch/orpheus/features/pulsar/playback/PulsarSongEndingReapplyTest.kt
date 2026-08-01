@file:OptIn(ExperimentalCoroutinesApi::class)

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
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.MutablePrefs
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.makeSongEnding
import org.balch.orpheus.features.pulsar.makeStubPlaybackController
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for "Fire Sky stuck on the outro". Pins two guarantees:
 * a re-apply of the *playing* vibe resets song-ending state and the sticky C++
 * `arrangement_outro_request` port, and a terminal outro that loops again after
 * `SongEnded` re-emits instead of stranding the song.
 */
class PulsarSongEndingReapplyTest {

    @Test
    fun `re-applying the current vibe clears the sticky C++ outro request`() = runTest {
        val harness = ReapplyHarness(this)
        harness.armAndEndSong()
        assertEquals(
            PortValue.IntValue(1),
            harness.outroRequestPort(),
            "sanity: arming must set the C++ outro request",
        )

        // Same vibe, re-applied — exactly what engineRecreatedFlow does.
        harness.feature.applyVibe(harness.vibe)
        advanceUntilIdle()

        assertEquals(
            PortValue.IntValue(0),
            harness.outroRequestPort(),
            "a vibe re-apply must clear the sticky outro request; otherwise C++ re-arms " +
                "outro_triggered at the next bar boundary and pins the arrangement to the outro",
        )
    }

    @Test
    fun `re-applying the current vibe disarms the outro and starts a fresh song`() = runTest {
        val harness = ReapplyHarness(this)
        harness.armAndEndSong()
        assertTrue(harness.songEnding.endingTriggeredForTest, "sanity: outro armed")

        harness.feature.applyVibe(harness.vibe)
        advanceUntilIdle()

        assertEquals(
            false,
            harness.songEnding.endingTriggeredForTest,
            "a re-apply starts a new song — it must not inherit the previous song's armed outro",
        )
        assertEquals(-1, harness.songEnding.finalSectionIndexForTest, "final section must reset")
        assertEquals(0L, harness.songEnding.playingMillisForTest, "playing-time must reset")
    }

    @Test
    fun `terminal outro looping again after SongEnded re-emits so the song can escape`() = runTest {
        val harness = ReapplyHarness(this)
        val events = mutableListOf<SongEndingEvent>()
        backgroundScope.launch {
            harness.songEnding.songEndingEvents.collect { events += it }
        }
        runCurrent()

        harness.armAndEndSong()
        assertEquals(
            1,
            events.filterIsInstance<SongEndingEvent.SongEnded>().size,
            "sanity: the first outro loop must end the song",
        )

        // No vibe change followed, so C++ keeps re-routing to outro_index.
        harness.playOutroLoop()

        assertEquals(
            2,
            events.filterIsInstance<SongEndingEvent.SongEnded>().size,
            "a second outro loop must re-emit SongEnded; the engine cannot leave the " +
                "outro on its own, so a latched emit strands the song forever",
        )
    }

    @Test
    fun `no duplicate SongEnded within a single outro pass`() = runTest {
        val harness = ReapplyHarness(this)
        val events = mutableListOf<SongEndingEvent>()
        backgroundScope.launch {
            harness.songEnding.songEndingEvents.collect { events += it }
        }
        runCurrent()

        harness.armAndEndSong()
        // Ticks that advance WITHIN the outro must not re-fire — the loop-back
        // edge is what ends the song, not merely being in the final section.
        harness.tickOutro(1)
        harness.tickOutro(2)
        harness.tickOutro(3)

        assertEquals(
            1,
            events.filterIsInstance<SongEndingEvent.SongEnded>().size,
            "SongEnded must be edge-triggered, one per outro loop — a re-emit every tick " +
                "would double-advance the vibe mid-transition",
        )
    }
}

/**
 * Fire-Sky-shaped vibe: a body section that loops, plus a TERMINAL outro
 * (no transitions) named as `outroIndex`. Only an outro request can reach it.
 */
private fun mkTerminalOutroVibe(name: String): Vibe =
    mkMinimalVibe(name).copy(
        arrangement = Arrangement(
            introIndex = 0,
            outroIndex = 1,
            lengthSeconds = 120..166,  // Fire Sky's window
            sections = listOf(
                Section(
                    name = "body",
                    transitions = listOf(SectionTransition(targetIndex = 0, weight = 1f)),
                ),
                Section(name = "outro"),  // terminal, like Fire Sky's
            ),
        ),
    )

private class ReapplyHarness(private val testScope: TestScope) {
    val vibe: Vibe = mkTerminalOutroVibe("Fire Sky")
    val feature = FakePulsarFeature(vibeList = listOf(vibe), initial = vibe)
    val prefs = MutablePrefs()

    val ports = mutableMapOf<String, PortValue>()
    val synthController: SynthController = SynthController().apply {
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
        random = { _, _ -> 0f }  // always roll into the trigger once past min
    }

    fun outroRequestPort(): PortValue? {
        val id = PulsarSymbol.ARRANGEMENT_OUTRO_REQUEST.controlId
        return ports["${id.uri}:${id.symbol}"]
    }

    /** One bar tick inside the outro section, without looping back. */
    fun tickOutro(barsElapsed: Int) = tick(1, barsElapsed)

    private fun tick(sectionIndex: Int, barsElapsed: Int) {
        feature.arrangement.value = PulsarArrangementState(
            sectionIndex = sectionIndex,
            barsElapsed = barsElapsed,
            barsTotal = 4,
            soloActive = false,
            soloTrack = -1,
            soloMode = 0,
        )
        testScope.runCurrent()
    }

    /** Arm past maxVibeSeconds, walk into the outro, then loop it once (= SongEnded). */
    fun armAndEndSong() {
        prefs.enabledFlow.value = true
        playbackController.play()
        testScope.advanceTimeBy(170_000L)  // > 166s max
        tick(0, 3)                          // trigger evaluation fires here
        assertTrue(songEnding.endingTriggeredForTest, "sanity: outro must arm past maxVibeSeconds")
        playOutroLoop()
    }

    /** Enter the terminal outro, run its bars, then loop back to bar 0. */
    fun playOutroLoop() {
        tick(1, 0)
        tick(1, 1)
        tick(1, 2)
        tick(1, 3)
        tick(1, 0)  // terminal outro self-loop — the "song ended" signal
        testScope.advanceUntilIdle()
    }
}
