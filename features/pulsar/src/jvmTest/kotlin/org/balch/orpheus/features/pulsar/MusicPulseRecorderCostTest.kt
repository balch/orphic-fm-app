package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * What the recorder allocates per closed beat early in a song and near its cap (scene S8 of the
 * perf audit), on the test thread: the unconfined dispatcher runs the recorder inline. A beat
 * must cost the same at the cap as at the start.
 *
 * ./gradlew :features:pulsar:jvmTest --tests '*MusicPulseRecorderCostTest*' --rerun
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPulseRecorderCostTest {
    private val viz = MutableStateFlow(PulsarVizData())
    private val engine = object : SongEndingStubSynthEngine() {
        override val pulsarVizFlow = viz
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = makeAppCoroutineScope(dispatcher)
    private val session = PulsarSession(engine, scope, FixturesDispatchers(dispatcher))
    private val time = TestTimeSource()
    private val recorder = MusicPulseRecorder(engine, session, scope, FixturesDispatchers(dispatcher), time)

    // One sample per step of a 16-step bar, pre-built so feeding them allocates nothing of the test's own.
    private val samples = Array(16) { step ->
        PulsarVizData(playheads = IntArray(8) { step }, trackLevels = FloatArray(8) { if (it == step % 8) 0.5f else 0.1f })
    }
    private var step = 0

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Feeds steps until the story holds [beats] beats. */
    private fun playUntil(beats: Int) {
        var fed = 0
        while (recorder.songStory.value.beats.size < beats) {
            check(fed++ < beats * 8) { "the story stalled at ${recorder.songStory.value.beats.size} beats" }
            time += 20.milliseconds
            viz.value = samples[step++ % samples.size]
        }
    }

    /** Bytes per beat while the story grows from [from] to [to] beats. */
    private fun bytesPerBeat(from: Int, to: Int): Long {
        playUntil(from)
        System.gc()
        val start = threads.currentThreadAllocatedBytes
        playUntil(to)
        return (threads.currentThreadAllocatedBytes - start) / (to - from)
    }

    @Test
    fun measureTheCostOfABeatOverTheSong() {
        // Unsupported or disabled, the count reads -1 every time and the budgets pass on nothing.
        check(threads.isThreadAllocatedMemorySupported) { "this JVM cannot count a thread's allocations" }
        check(threads.isThreadAllocatedMemoryEnabled) { "thread allocation counting is disabled" }
        // As a live wave reads it: the pulse is built for every sample.
        val hold = recorder.holdPulse()
        session.updateProgress(PlaybackProgress(positionMs = 0L, durationMs = 40 * 60_000L))
        // Warm the JIT on a song's worth of beats first, then start a fresh song.
        playUntil(400)
        session.updateVibe(mkMinimalVibe("Next"))
        // A position the old song never had, or the progress flow would not emit it again.
        session.updateProgress(PlaybackProgress(positionMs = 1_000L, durationMs = 40 * 60_000L))
        assertEquals(0, recorder.songStory.value.beats.size, "sanity: the new song kept the old story")
        val early = bytesPerBeat(100, 200)
        val late = bytesPerBeat(3_900, 4_000)
        println("[frame-budget] S8 story: $early B/beat for beats 100-200, $late B/beat for beats 3900-4000")
        assertTrue(early < PerBeatBudget && late < PerBeatBudget, "a beat cost $early B early and $late B late")
        assertTrue(late - early < GrowthBudget, "a beat costs ${late - early} B more near the cap: the story is copied again")
        // TV hardware: nothing holds the pulse, so only the story is kept.
        hold.dispose()
        session.updateVibe(mkMinimalVibe("Unheld"))
        session.updateProgress(PlaybackProgress(positionMs = 2_000L, durationMs = 40 * 60_000L))
        val unheld = bytesPerBeat(100, 200)
        println("[frame-budget] S8 story, pulse unheld: $unheld B/beat for beats 100-200")
    }

    private companion object {
        /** A beat's cost with the pulse held, early or late: measured 2.09 KB (full-suite run, 2026-09-25), about twice that. */
        const val PerBeatBudget = 4_140L

        /** Late over early: measured 8 B in the same run; copying the story, as before, cost 30 KB more near the cap. */
        const val GrowthBudget = 512L
    }
}
