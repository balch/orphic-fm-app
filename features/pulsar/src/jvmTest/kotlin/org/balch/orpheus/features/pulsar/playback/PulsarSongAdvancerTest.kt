package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSongAdvancerTest {

    private class FakeSongEndingEventSource : SongEndingEventSource {
        val emitter = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 8)
        override val songEndingEvents: SharedFlow<SongEndingEvent> = emitter.asSharedFlow()
    }

    /** In-memory SynthController stub. Enough surface for advancer tests. */
    private fun stubSynthController(): SynthController = SynthController().apply {
        val ports = mutableMapOf<String, PortValue>()
        setDelegates(
            setter = { id, value -> ports["${id.uri}:${id.symbol}"] = value; true },
            getter = { id -> ports["${id.uri}:${id.symbol}"] },
        )
    }

    /** Default fixture: zero gap so tests that don't care about timing run instantly. */
    private fun makeAdvancer(
        feature: FakePulsarFeature,
        source: FakeSongEndingEventSource,
        engine: SongEndingStubSynthEngine = SongEndingStubSynthEngine(),
        controller: SynthController = stubSynthController(),
    ): PulsarSongAdvancer = PulsarSongAdvancer(
        feature, source, engine, controller, makeAppCoroutineScope(),
    ).apply { interTrackGapMs = 0 }

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
    fun `gap halts beats and silence then advances at gap end`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val source = FakeSongEndingEventSource()
        val engine = SongEndingStubSynthEngine()
        val controller = stubSynthController()
        // Wire the advancer onto the runTest scheduler so delay is virtualized.
        val advancer = PulsarSongAdvancer(
            feature, source, engine, controller,
            makeAppCoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ).apply { interTrackGapMs = 2_000L }
        @Suppress("UNUSED_EXPRESSION") advancer

        source.emitter.tryEmit(SongEndingEvent.SongEnded("A"))
        // Just after emit: muted, beats halted, still on A.
        advanceTimeBy(10L)
        assertEquals("A", feature.vibeFlow.value.name, "vibe should not flip during gap")
        assertEquals(0f, engine.getMasterVolume(), "gap should silence master volume")
        assertEquals(
            PortValue.IntValue(0),
            controller.getPluginControl(PulsarSymbol.PLAYING.controlId),
            "PULSAR_PLAYING should be 0 during gap so beats halt",
        )

        // Just before gap completes: still A.
        advanceTimeBy(1_980L)
        assertEquals("A", feature.vibeFlow.value.name, "vibe should still hold at 1.99s")

        // Past the gap: vibe advances, volume restores, beats resume.
        advanceTimeBy(50L)
        assertEquals("B", feature.vibeFlow.value.name, "vibe should advance after gap")
        assertEquals(1.0f, engine.getMasterVolume(), "volume restored to unity")
        assertEquals(
            PortValue.IntValue(1),
            controller.getPluginControl(PulsarSymbol.PLAYING.controlId),
            "PULSAR_PLAYING should be 1 after applyVibe",
        )
    }
}
