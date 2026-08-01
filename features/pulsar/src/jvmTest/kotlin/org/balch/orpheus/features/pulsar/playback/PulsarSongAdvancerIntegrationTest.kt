package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration test for the song-advance transition path. Wires a
 * real [PulsarSongAdvancer] against the REAL [PulsarTransitionRunnerImpl]
 * (no fake runner) so we exercise the full chain:
 *
 *   SongEnded → resolved [TransitionSpec] → runTransition (with style-specific
 *   engine fadeMasterVolume / masterTapeStop calls) → applyVibeByName.
 *
 * Complements [PulsarSongAdvancerTest] (unit-level coverage with a fake
 * runner) and [PulsarSongEndingIntegrationTest] (which covers the upstream
 * outro-trigger pipeline that emits SongEnded in the first place).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSongAdvancerIntegrationTest {

    /** Fake event source so tests can emit `SongEnded` synchronously into the advancer. */
    private class FakeEventSource : SongEndingEventSource {
        private val _events = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 4)
        override val songEndingEvents: SharedFlow<SongEndingEvent> = _events.asSharedFlow()
        override val finalSectionIndex: kotlinx.coroutines.flow.StateFlow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(-1)
        override val endingTriggered: kotlinx.coroutines.flow.StateFlow<Boolean> =
            kotlinx.coroutines.flow.MutableStateFlow(false)
        val resolvedStyleFlow = kotlinx.coroutines.flow.MutableStateFlow(TransitionStyle.FADE)
        override val resolvedTransitionStyle: kotlinx.coroutines.flow.StateFlow<TransitionStyle> =
            resolvedStyleFlow
        override fun armOutro() {}
        override fun onVibeApplied() {}
        suspend fun emit(event: SongEndingEvent) = _events.emit(event)
    }

    /**
     * Recording engine for the integration test. Extends [SongEndingStubSynthEngine]
     * so we get full [org.balch.orpheus.core.audio.SynthEngine] coverage, but
     * captures every `fadeMasterVolume` and `masterTapeStop` call for assertion.
     */
    private class RecordingEngine(initialVolume: Float = 1.0f) : SongEndingStubSynthEngine() {
        data class FadeCall(val target: Float, val durationMs: Int, val curve: FadeCurve)
        val fadeCalls = mutableListOf<FadeCall>()
        val tapeStopCalls = mutableListOf<Int>()

        init { setMasterVolume(initialVolume) }

        override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) {
            fadeCalls += FadeCall(target, durationMs, curve)
            setMasterVolume(target)
        }

        override fun masterTapeStop(durationMs: Int) {
            tapeStopCalls += durationMs
            // NOTE: The real C++ MasterTapeStop only ramps playback rate — it does
            // NOT touch the MasterFader. The fake must not side-effect `vol` here
            // or the SCRATCH fade-in would appear to ramp 0 → 1 in tests while
            // silently no-opping 1 → 1 in production.
        }
    }

    /**
     * Build an advancer wired to the real [PulsarTransitionRunnerImpl] on the
     * runTest scheduler so per-style `delay(...)` calls virtualize.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.makeAdvancer(
        feature: FakePulsarFeature,
        source: FakeEventSource,
        prefs: StubTransitionPreferences,
        engine: RecordingEngine,
    ): PulsarSongAdvancer {
        val scope = makeAppCoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val runner = PulsarTransitionRunnerImpl(engine, random = { it.first() })
        return PulsarSongAdvancer(feature, source, prefs, runner, scope)
    }

    @Test
    fun `FADE style end-to-end fires fades and applies next vibe`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val events = FakeEventSource()
        val engine = RecordingEngine()
        val prefs = StubTransitionPreferences(TransitionSpec(TransitionStyle.FADE, handoffMs = 200))
        @Suppress("UNUSED_VARIABLE") val advancer = makeAdvancer(feature, events, prefs, engine)

        events.emit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()

        assertEquals("B", feature.vibeFlow.value.name, "advancer should flip to next vibe")
        // FADE@200 splits into 2x100ms halves: fade-out to 0, swap, fade-in to 1.
        assertEquals(2, engine.fadeCalls.size, "FADE fires 2 fades, got ${engine.fadeCalls}")
        assertEquals(0f, engine.fadeCalls[0].target)
        assertEquals(100, engine.fadeCalls[0].durationMs)
        assertEquals(1f, engine.fadeCalls[1].target)
        assertEquals(100, engine.fadeCalls[1].durationMs)
        assertTrue(engine.tapeStopCalls.isEmpty(), "FADE should not arm tape stop")
    }

    @Test
    fun `SCRATCH style end-to-end arms tape stop and applies next vibe`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val events = FakeEventSource()
        val engine = RecordingEngine()
        val prefs = StubTransitionPreferences(TransitionSpec(TransitionStyle.TAPE, handoffMs = 250))
        @Suppress("UNUSED_VARIABLE") val advancer = makeAdvancer(feature, events, prefs, engine)

        // Mirror PulsarSongEnding's resolution so the advancer sees TAPE.
        events.resolvedStyleFlow.value = TransitionStyle.TAPE
        events.emit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()

        assertEquals("B", feature.vibeFlow.value.name)
        assertEquals(listOf(250), engine.tapeStopCalls, "SCRATCH arms one tape stop at handoffMs")
        // SCRATCH fires two fades: a 1-sample snap-to-0 (the C++ MasterTapeStop
        // only ramps playback rate, not the MasterFader, so we must explicitly
        // snap the fader to 0 before applyNext), then the 100ms fade-in back to 1.
        assertEquals(2, engine.fadeCalls.size, "SCRATCH fires snap-to-0 then fade-in")
        assertEquals(0f, engine.fadeCalls[0].target)
        assertEquals(1, engine.fadeCalls[0].durationMs)
        assertEquals(1f, engine.fadeCalls[1].target)
        assertEquals(100, engine.fadeCalls[1].durationMs)
    }

    @Test
    fun `disabled advancer ignores SongEnded events`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val events = FakeEventSource()
        val engine = RecordingEngine()
        val prefs = StubTransitionPreferences(TransitionSpec(TransitionStyle.FADE))
        val advancer = makeAdvancer(feature, events, prefs, engine)
        advancer.enabled = false

        events.emit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()

        assertEquals("A", feature.vibeFlow.value.name, "disabled advancer must not advance")
        assertTrue(engine.fadeCalls.isEmpty(), "no transition fades when disabled")
        assertTrue(engine.tapeStopCalls.isEmpty(), "no tape stop when disabled")
    }

    @Test
    fun `per-vibe transitionOut wins over global default`() = runTest {
        // vibeA has explicit CUT transitionOut; global default is FADE@350.
        // The advancer must pick the per-vibe spec → runner runs CUT → no fades.
        val vibeA = mkMinimalVibe(
            name = "A",
            transitionOut = TransitionSpec(TransitionStyle.CUT),
        )
        val vibes = listOf(vibeA, mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val events = FakeEventSource()
        val engine = RecordingEngine()
        val prefs = StubTransitionPreferences(TransitionSpec(TransitionStyle.FADE, handoffMs = 350))
        @Suppress("UNUSED_VARIABLE") val advancer = makeAdvancer(feature, events, prefs, engine)

        // Mirror PulsarSongEnding's resolution: vibe A overrides to CUT.
        events.resolvedStyleFlow.value = TransitionStyle.CUT
        events.emit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()

        assertEquals("B", feature.vibeFlow.value.name, "per-vibe CUT still advances")
        assertTrue(
            engine.fadeCalls.isEmpty(),
            "CUT should fire no fades, got ${engine.fadeCalls}",
        )
        assertTrue(
            engine.tapeStopCalls.isEmpty(),
            "CUT should fire no tape stops, got ${engine.tapeStopCalls}",
        )
    }
}
