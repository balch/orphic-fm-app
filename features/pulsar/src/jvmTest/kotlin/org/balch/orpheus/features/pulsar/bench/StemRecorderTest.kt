package org.balch.orpheus.features.pulsar.bench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.audio.dsp.NoStemTap
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.audio.dsp.StemTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.FixturesDispatchers
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StemRecorderTest {
    private val vibe = mkMinimalVibe("stems").let { v ->
        v.copy(
            arrangement = Arrangement(sections = listOf(Section("intro"), Section("drop"))),
            tracks = v.tracks.mapIndexed { i, t ->
                if (i == 2) t.copy(engineSpace = OrpheusEngine(engineId = OrpheusEngineId.FM)) else t
            },
        )
    }
    private val feature = FakePulsarFeature(listOf(vibe), vibe).apply {
        stateFlow.value = stateFlow.value.copy(playing = true)
        arrangement.value = PulsarArrangementState(1, 0, 0, false, -1, 0)
    }
    private val synth = SynthController()
    private val tap = FakeStemTap()
    private lateinit var controller: MixBenchController

    /** Every frame the sink saw, as the first float of each (its frame index). */
    private val received = mutableListOf<Float>()
    private val sink = StemSink { frames, count ->
        for (f in 0 until count) received += frames[f * StemLayout.FLOATS_PER_FRAME]
    }

    private suspend fun TestScope.recorder(stemTap: StemTap = tap): StemRecorder {
        controller = MixBenchController(feature, synth, NoMeterTap, backgroundScope, meterIdleMs = 1_000).also {
            it.start()
            runCurrent()
        }
        // The native ring records only while the mix bench meters.
        tap.gate = { controller.isMetering }
        tap.renderIn(backgroundScope)
        val io = FixturesDispatchers(StandardTestDispatcher(testScheduler))
        return StemRecorder(controller, { feature }, stemTap, sampleRate = 48_000, dispatchers = io)
    }

    @Test
    fun recordsRequestedFramesInOrder() = runTest {
        val r = recorder().record(0.5f, sink)
        assertTrue(r.ok, r.message)
        assertEquals(24_000, r.frames)
        assertEquals(0L, r.droppedFrames)
        assertEquals((0 until 24_000).map { it.toFloat() }, received)
        assertEquals(listOf(24_000), tap.arms)
        assertEquals(listOf(true, false), tap.enableCalls)
        assertEquals("stems", r.vibe)
        assertEquals("drop", r.sectionAtStart)
        val va = OrpheusEngineId.VA.displayName
        assertEquals(List(8) { if (it == 2) "$va / ${OrpheusEngineId.FM.displayName}" else va }, r.trackLabels)
    }

    @Test
    fun refusesWhenStopped() = runTest {
        feature.stateFlow.value = feature.stateFlow.value.copy(playing = false)
        val r = recorder().record(0.5f, sink)
        assertFalse(r.ok)
        assertTrue("press play" in r.message, r.message)
        assertTrue(tap.enableCalls.isEmpty())
    }

    @Test
    fun secondRecordIsRefused() = runTest {
        val rec = recorder()
        val first = async { rec.record(2f, sink) }
        advanceTimeBy(100); runCurrent()
        val second = rec.record(0.5f, StemSink { _, _ -> error("the refused capture wrote") })
        assertFalse(second.ok)
        assertTrue("already running" in second.message, second.message)
        val r = first.await()
        assertTrue(r.ok, r.message)
        assertEquals(96_000, r.frames)
        assertEquals((0 until 96_000).map { it.toFloat() }, received)
    }

    @Test
    fun refusalMatchesWhatRecordWouldSay() = runTest {
        val rec = recorder()
        assertEquals(null, rec.refusal())
        val first = async { rec.record(1f, sink) }
        advanceTimeBy(100); runCurrent()
        assertTrue("already running" in rec.refusal().orEmpty())
        assertTrue(first.await().ok)
        feature.stateFlow.value = feature.stateFlow.value.copy(playing = false)
        assertTrue("press play" in rec.refusal().orEmpty())
    }

    @Test
    fun vibeLoadAbortsAndCleansUp() = runTest {
        val rec = recorder()
        val run = async { rec.record(2f, sink) }
        advanceTimeBy(500); runCurrent()
        feature.applyVibe(vibe)
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("vibe changed" in r.message, r.message)
        assertEquals(listOf(true, false), tap.enableCalls)
        assertFalse(tap.enabled)
        // The hold is gone, so metering idles off as usual.
        advanceTimeBy(1_001); runCurrent()
        assertFalse(controller.isMetering)
    }

    @Test
    fun vibeLoadInTheLastPollAborts() = runTest {
        val rec = recorder()
        // The load lands while the final frames drain, after the loop's own check for that poll.
        val r = rec.record(
            0.5f,
            StemSink { frames, count ->
                sink.write(frames, count)
                if (received.size == 24_000) feature.applyVibe(vibe)
            },
        )
        assertEquals(24_000, received.size)
        assertFalse(r.ok)
        assertTrue("vibe changed" in r.message, r.message)
        assertEquals(listOf(true, false), tap.enableCalls)
    }

    @Test
    fun cancelMidRecordCleansUp() = runTest {
        val rec = recorder()
        val run = async { rec.record(2f, sink) }
        advanceTimeBy(500); runCurrent()
        assertTrue(controller.isMetering)
        run.cancelAndJoin()
        assertEquals(listOf(true, false), tap.enableCalls)
        assertFalse(tap.enabled)
        // The hold is released, so metering idles off; the busy lock is free for the next capture.
        advanceTimeBy(1_001); runCurrent()
        assertFalse(controller.isMetering)
        val next = rec.record(0.5f, sink)
        assertTrue(next.ok, next.message)
        assertEquals(24_000, next.frames)
    }

    @Test
    fun closeEndsTheRunningCaptureAndRefusesLaterOnes() = runTest {
        val rec = recorder()
        val run = async { rec.record(2f, sink) }
        advanceTimeBy(500); runCurrent()
        rec.close()
        // close returns only after the capture disabled the ring.
        assertEquals(listOf(true, false), tap.enableCalls)
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("closing" in r.message, r.message)
        val next = rec.record(0.5f, StemSink { _, _ -> error("a capture ran after close") })
        assertFalse(next.ok)
        assertTrue("closing" in next.message, next.message)
        assertEquals(listOf(true, false), tap.enableCalls)
    }

    @Test
    fun playbackStopMidCaptureAborts() = runTest {
        val rec = recorder()
        val run = async { rec.record(2f, sink) }
        advanceTimeBy(500); runCurrent()
        feature.stateFlow.value = feature.stateFlow.value.copy(playing = false)
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("playback stopped" in r.message, r.message)
        assertTrue(r.frames in 1 until 96_000, "frames: ${r.frames}")
        assertEquals(listOf(true, false), tap.enableCalls)
    }

    @Test
    fun engineClosingMidCaptureIsCutShort() = runTest {
        val rec = recorder()
        val run = async { rec.record(2f, sink) }
        advanceTimeBy(500); runCurrent()
        // remaining() reads 0 early, as the native pass-throughs do once the engine is gone.
        tap.engineClosed = true
        val r = run.await()
        assertFalse(r.ok)
        assertTrue("cut short" in r.message, r.message)
        assertTrue(r.frames in 1 until 96_000, "frames: ${r.frames}")
        assertEquals(listOf(true, false), tap.enableCalls)
    }

    @Test
    fun droppedFramesReported() = runTest {
        tap.dropNext = 480
        val r = recorder().record(0.5f, sink)
        assertTrue(r.ok, r.message)
        assertEquals(480L, r.droppedFrames)
        assertEquals(24_000 - 480, r.frames)
        assertTrue("dropped" in r.message, r.message)
    }

    @Test
    fun unsupportedTap() = runTest {
        val r = recorder(NoStemTap).record(0.5f, sink)
        assertFalse(r.ok)
        assertTrue("isn't available" in r.message, r.message)
    }

    @Test
    fun staleLibraryIsRefusedAndHoldReleased() = runTest {
        tap.libraryHasRing = false
        val rec = recorder()
        val r = rec.record(0.5f, sink)
        assertFalse(r.ok)
        assertTrue("rebuild" in r.message, r.message)
        advanceTimeBy(1_001); runCurrent()
        assertFalse(controller.isMetering)
    }

    @Test
    fun noAudioArrivingAborts() = runTest {
        val rec = recorder()
        // Nothing renders: the ring never counts down, so the capture must not wait forever.
        tap.gate = { false }
        val r = rec.record(0.5f, sink)
        assertFalse(r.ok)
        assertTrue("no audio" in r.message, r.message)
        assertEquals(listOf(true, false), tap.enableCalls)
    }

    @Test
    fun progressIsThrottled() = runTest {
        val seen = mutableListOf<Float>()
        val r = recorder().record(2f, sink) { seen += it }
        assertTrue(r.ok, r.message)
        assertTrue(seen.size in 3..5, "progress calls: $seen")
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.all { it in 0f..1f }, "progress: $seen")
    }
}
