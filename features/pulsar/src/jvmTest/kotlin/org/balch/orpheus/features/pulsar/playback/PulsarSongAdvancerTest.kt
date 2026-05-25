package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSongAdvancerTest {

    private class FakeSongEndingEventSource : SongEndingEventSource {
        val emitter = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 8)
        override val songEndingEvents: SharedFlow<SongEndingEvent> = emitter.asSharedFlow()
        override val finalSectionIndex: kotlinx.coroutines.flow.StateFlow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(-1)
        override val endingTriggered: kotlinx.coroutines.flow.StateFlow<Boolean> =
            kotlinx.coroutines.flow.MutableStateFlow(false)
        val resolvedStyleFlow = kotlinx.coroutines.flow.MutableStateFlow(TransitionStyle.FADE)
        override val resolvedTransitionStyle: kotlinx.coroutines.flow.StateFlow<TransitionStyle> =
            resolvedStyleFlow
        override fun armOutro() {}
    }

    /** Records calls into the runner so tests can assert on resolved [TransitionSpec]. */
    private class RecordingRunner : PulsarTransitionRunner {
        val specs = mutableListOf<TransitionSpec>()
        override val activeStyle: kotlinx.coroutines.flow.StateFlow<TransitionStyle?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)
        override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
            specs += spec
            applyNext()
        }
    }

    /** Default fixture: CUT-style runner so applyNext runs immediately, no virtual delays. */
    private fun makeAdvancer(
        feature: FakePulsarFeature,
        source: FakeSongEndingEventSource,
        prefs: StubTransitionPreferences = StubTransitionPreferences(),
        runner: PulsarTransitionRunner = RecordingRunner(),
    ): PulsarSongAdvancer = PulsarSongAdvancer(
        feature, source, prefs, runner, makeAppCoroutineScope(),
    )

    @Test
    fun `advances to next vibe in list on SongEnded`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        val advancer = makeAdvancer(feature, source)
        advanceUntilIdle()
        @Suppress("UNUSED_EXPRESSION") advancer

        source.emitter.tryEmit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()
        assertEquals("B", feature.vibeFlow.value.name)

        source.emitter.tryEmit(SongEndingEvent.SongEnded("B"))
        advanceUntilIdle()
        assertEquals("C", feature.vibeFlow.value.name)

        source.emitter.tryEmit(SongEndingEvent.SongEnded("C"))
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name)
    }

    @Test
    fun `disabled advancer does not advance`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        val advancer = makeAdvancer(feature, source)
        advancer.enabled = false

        source.emitter.tryEmit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name)
    }

    @Test
    fun `OutroTriggered does not advance`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        @Suppress("UNUSED_VARIABLE", "unused")
        val advancer = makeAdvancer(feature, source)

        source.emitter.tryEmit(SongEndingEvent.OutroTriggered("A"))
        advanceUntilIdle()
        assertEquals("A", feature.vibeFlow.value.name)
    }

    @Test
    fun `uses global default transition spec when vibe has no override`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        val prefs = StubTransitionPreferences(initial = TransitionSpec(TransitionStyle.CROSSFADE))
        val runner = RecordingRunner()
        val advancer = PulsarSongAdvancer(feature, source, prefs, runner, makeAppCoroutineScope())
        @Suppress("UNUSED_EXPRESSION") advancer

        // Mirror what PulsarSongEnding would set in production: the advancer
        // now reads the resolved style from source, not from prefs/vibe.
        source.resolvedStyleFlow.value = TransitionStyle.CROSSFADE
        source.emitter.tryEmit(SongEndingEvent.SongEnded("A"))
        advanceUntilIdle()

        assertEquals(1, runner.specs.size)
        assertEquals(TransitionStyle.CROSSFADE, runner.specs.single().style)
        assertEquals("B", feature.vibeFlow.value.name)
    }

    @Test
    fun `uses per-vibe transitionOut when set`() = runTest {
        // vibeA has its own GAP override; the global default is FADE — runner should see GAP.
        val vibeAOverride = TransitionSpec(TransitionStyle.GAP, handoffMs = 100)
        val baseVibeA = mkMinimalVibe("A")
        val vibeA = baseVibeA.copy(
            arrangement = baseVibeA.arrangement?.copy(transitionOut = vibeAOverride),
        )
        val vibes = listOf(vibeA, mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        val prefs = StubTransitionPreferences(initial = TransitionSpec(TransitionStyle.FADE))
        val runner = RecordingRunner()
        // Wire the advancer onto the runTest scheduler so any internal delays virtualize.
        val advancer = PulsarSongAdvancer(
            feature, source, prefs, runner,
            makeAppCoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        @Suppress("UNUSED_EXPRESSION") advancer

        // Mirror PulsarSongEnding's resolution: vibe A overrides to GAP.
        source.resolvedStyleFlow.value = TransitionStyle.GAP
        source.emitter.tryEmit(SongEndingEvent.SongEnded("A"))
        advanceTimeBy(10L)
        advanceUntilIdle()

        assertEquals(1, runner.specs.size)
        assertEquals(TransitionStyle.GAP, runner.specs.single().style)
        assertEquals(100, runner.specs.single().handoffMs)
        assertEquals("B", feature.vibeFlow.value.name)
    }
}
