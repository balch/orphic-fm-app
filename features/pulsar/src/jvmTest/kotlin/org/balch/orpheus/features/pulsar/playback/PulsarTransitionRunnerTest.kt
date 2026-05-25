package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.SongEndingStubSynthEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [PulsarTransitionRunner]. Drives each transition style with a
 * fake [org.balch.orpheus.core.audio.SynthEngine] that records `(time, target,
 * durationMs, curve)` tuples for assertion. Uses the kotlinx-coroutines
 * `runTest` virtual clock so per-style timelines are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulsarTransitionRunnerTest {

    /** Records master-fader calls. Extends the shared stub so we get full SynthEngine coverage. */
    private class RecordingEngine(initialVolume: Float = 1.0f) : SongEndingStubSynthEngine() {
        data class FadeCall(val timeMs: Long, val target: Float, val durationMs: Int, val curve: FadeCurve)
        val fades = mutableListOf<FadeCall>()
        val tapeStops = mutableListOf<Pair<Long, Int>>()
        val scratches = mutableListOf<Pair<Long, Int>>()
        val djSweeps = mutableListOf<Pair<Long, Int>>()
        var now: () -> Long = { 0L }

        init {
            setMasterVolume(initialVolume)
        }

        override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) {
            fades += FadeCall(now(), target, durationMs, curve)
            setMasterVolume(target)
        }

        override fun masterTapeStop(durationMs: Int) {
            tapeStops += now() to durationMs
        }

        override fun masterScratch(durationMs: Int) {
            scratches += now() to durationMs
        }

        override fun masterDjSweep(durationMs: Int) {
            djSweeps += now() to durationMs
        }
    }

    private fun makeRunner(engine: RecordingEngine): PulsarTransitionRunnerImpl =
        PulsarTransitionRunnerImpl(engine, random = { items -> items.first() })

    @Test
    fun `CUT calls applyNext at t=0 with no fades`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        runner.runTransition(TransitionSpec(TransitionStyle.CUT)) {
            applyAt += testScheduler.currentTime
        }
        assertEquals(listOf(0L), applyAt)
        assertTrue(engine.fades.isEmpty())
        assertTrue(engine.tapeStops.isEmpty())
    }

    @Test
    fun `GAP with default handoff fires expected sequence`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.GAP)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(2, engine.fades.size)
        assertEquals(0f, engine.fades[0].target)
        assertEquals(80, engine.fades[0].durationMs)
        assertEquals(listOf(80L), applyAt) // applyNext after DECLICK_MS
        assertEquals(1f, engine.fades[1].target)
        assertEquals(80, engine.fades[1].durationMs)
    }

    @Test
    fun `FADE default fires fade-out, applyNext, fade-in symmetrically`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.FADE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(2, engine.fades.size)
        assertEquals(0f, engine.fades[0].target)
        assertEquals(175, engine.fades[0].durationMs) // 350 / 2
        assertEquals(listOf(175L), applyAt)
        assertEquals(1f, engine.fades[1].target)
        assertEquals(175, engine.fades[1].durationMs)
    }

    @Test
    fun `FADE smart-skip avoids redundant fade-out when already silent`() = runTest {
        val engine = RecordingEngine(initialVolume = 0.02f) // already faded by outroBars
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.FADE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(1, engine.fades.size) // only fade-in
        assertEquals(1f, engine.fades[0].target)
        assertEquals(listOf(0L), applyAt) // applied immediately
    }

    @Test
    fun `CROSSFADE drops to 0_5 then rises to 1 with applyNext in between`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.CROSSFADE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(2, engine.fades.size)
        assertEquals(0.5f, engine.fades[0].target)
        assertEquals(200, engine.fades[0].durationMs) // 400 / 2
        assertEquals(listOf(200L), applyAt)
        assertEquals(1f, engine.fades[1].target)
    }

    @Test
    fun `CROSSFADE restores volume to unity if fader is below threshold`() = runTest {
        // CROSSFADE needs the outgoing audible to overlap with the incoming.
        // If the fader is already below unity (RANDOM-picks-CROSSFADE after a
        // non-zero outroBars, manual skip mid-fade, etc) the dip-to-0.5 would
        // be inaudible because the outgoing is already silent. Defensive snap
        // restores to 1.0 first so the crossfade character is preserved.
        val engine = RecordingEngine(initialVolume = 0.0f)
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.CROSSFADE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()

        // 3 fades: snap-to-1, dip-to-0.5, rise-to-1.
        assertEquals(3, engine.fades.size, "expected snap-to-1, dip-to-0.5, rise-to-1 (got ${engine.fades})")
        assertEquals(1f, engine.fades[0].target, "must restore to unity first")
        assertEquals(1, engine.fades[0].durationMs)
        assertEquals(0.5f, engine.fades[1].target)
        assertEquals(200, engine.fades[1].durationMs)
        assertEquals(1f, engine.fades[2].target)
        assertEquals(200, engine.fades[2].durationMs)
    }

    @Test
    fun `SCRATCH arms tape stop then applies new vibe`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.TAPE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(0L to 300), engine.tapeStops)
        assertEquals(listOf(300L), applyAt)
        // Two fades: a 1-sample snap-to-0 (so the fader's current_ is at 0 when
        // the new vibe arrives — MasterTapeStop only ramps playback rate, not
        // the fader), then the 100ms fade-in back to 1.
        assertEquals(2, engine.fades.size)
        assertEquals(0f, engine.fades[0].target)
        assertEquals(1, engine.fades[0].durationMs)
        assertEquals(1f, engine.fades[1].target)
        assertEquals(100, engine.fades[1].durationMs)
    }

    @Test
    fun `TAPE restores volume to unity if fader is below threshold`() = runTest {
        val engine = RecordingEngine(initialVolume = 0.0f) // outroBars already faded us out
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.TAPE)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()

        // First fade is a 1-sample snap back to 1.0 before the tape-stop arms.
        assertEquals(3, engine.fades.size, "expected snap-to-1, snap-to-0, fade-in (got ${engine.fades})")
        assertEquals(1f, engine.fades[0].target, "must restore to unity first")
        assertEquals(1, engine.fades[0].durationMs)
        // Tape-stop fires AFTER the unity restore.
        assertEquals(listOf(0L to 300), engine.tapeStops)
        assertEquals(listOf(300L), applyAt)
        // Then the snap-to-0 and the 100ms fade-in.
        assertEquals(0f, engine.fades[1].target)
        assertEquals(1, engine.fades[1].durationMs)
        assertEquals(1f, engine.fades[2].target)
        assertEquals(100, engine.fades[2].durationMs)
    }

    @Test
    fun `RANDOM picks from explicit pool`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val picked = mutableListOf<TransitionStyle>()
        val runner = PulsarTransitionRunnerImpl(engine, random = { items ->
            picked += items.first(); items.first()
        })
        val spec = TransitionSpec(TransitionStyle.RANDOM, randomPool = listOf(TransitionStyle.CUT))
        val applyAt = mutableListOf<Long>()
        runner.runTransition(spec) { applyAt += testScheduler.currentTime }
        assertEquals(listOf(TransitionStyle.CUT), picked)
        assertEquals(listOf(0L), applyAt)
    }

    @Test
    fun `RANDOM falls back to safe pool when randomPool empty`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val seen = mutableListOf<List<TransitionStyle>>()
        val runner = PulsarTransitionRunnerImpl(engine, random = { items ->
            seen += items; items.first()
        })
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.RANDOM)) {} }
        advanceUntilIdle()
        job.join()
        assertEquals(1, seen.size)
        assertEquals(
            listOf(
                TransitionStyle.CUT,
                TransitionStyle.GAP,
                TransitionStyle.FADE,
                TransitionStyle.CROSSFADE,
                TransitionStyle.TAPE,
                TransitionStyle.SCRATCH,
                TransitionStyle.DJ,
            ),
            seen[0],
        )
    }

    @Test
    fun `cancelling mid-transition leaves no half-applied state`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        var applied = false
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.FADE)) { applied = true } }
        advanceTimeBy(50L) // mid fade-out
        job.cancelAndJoin()
        assertFalse(applied)
        val job2 = launch { runner.runTransition(TransitionSpec(TransitionStyle.CUT)) { applied = true } }
        advanceUntilIdle()
        job2.join()
        assertTrue(applied)
    }

    @Test
    fun `activeStyle reports the running style and clears on completion`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        // Initially idle.
        assertEquals(null, runner.activeStyle.value)

        val job = launch {
            runner.runTransition(TransitionSpec(TransitionStyle.FADE)) {
                // At applyNext time the runner is mid-flight — activeStyle must
                // report FADE so the status overlay can label it correctly.
                assertEquals(TransitionStyle.FADE, runner.activeStyle.value)
            }
        }
        advanceUntilIdle()
        job.join()

        // Cleared after the run completes.
        assertEquals(null, runner.activeStyle.value)
    }

    @Test
    fun `SCRATCH arms effect and dips fader like CROSSFADE`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.SCRATCH)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(0L to 500), engine.scratches, "scratch armed at t=0 for default 500ms")
        assertEquals(listOf(250L), applyAt, "vibe swap at midpoint")
        assertEquals(2, engine.fades.size, "dip to 0.5 then back to 1")
        assertEquals(0.5f, engine.fades[0].target)
        assertEquals(1f, engine.fades[1].target)
    }

    @Test
    fun `DJ arms sweep and dips fader like CROSSFADE`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.DJ)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(0L to 1000), engine.djSweeps, "DJ sweep armed at t=0 for default 1000ms")
        assertEquals(listOf(500L), applyAt, "vibe swap at midpoint")
        assertEquals(2, engine.fades.size, "dip to 0.5 then back to 1")
        assertEquals(0.5f, engine.fades[0].target)
        assertEquals(1f, engine.fades[1].target)
    }

    @Test
    fun `DJ restores volume to unity if fader is below threshold`() = runTest {
        val engine = RecordingEngine(initialVolume = 0.0f)
        engine.now = { testScheduler.currentTime }
        val runner = makeRunner(engine)
        val applyAt = mutableListOf<Long>()
        val job = launch { runner.runTransition(TransitionSpec(TransitionStyle.DJ)) { applyAt += testScheduler.currentTime } }
        advanceUntilIdle()
        job.join()
        // 3 fades: snap-to-1, dip-to-0.5, rise-to-1
        assertEquals(3, engine.fades.size, "expected snap-to-1, dip-to-0.5, rise-to-1 (got ${engine.fades})")
        assertEquals(1f, engine.fades[0].target, "must restore to unity first")
        assertEquals(1, engine.fades[0].durationMs)
        assertEquals(0.5f, engine.fades[1].target)
        assertEquals(1f, engine.fades[2].target)
    }

    @Test
    fun `activeStyle reports resolved substyle for RANDOM (never RANDOM itself)`() = runTest {
        val engine = RecordingEngine()
        engine.now = { testScheduler.currentTime }
        val runner = PulsarTransitionRunnerImpl(engine, random = { TransitionStyle.CUT })
        var observed: TransitionStyle? = null
        runner.runTransition(TransitionSpec(TransitionStyle.RANDOM)) {
            observed = runner.activeStyle.value
        }
        // CUT, not RANDOM — surfacing "RANDOM" briefly would be a meaningless
        // flash since the user wants to see what was actually picked.
        assertEquals(TransitionStyle.CUT, observed)
    }
}
