package org.balch.orpheus.core.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs on a booted simulator against the real AVFoundation synthesizer, so it catches what a
 * compile cannot: that buffers arrive at all, and in a format we can read.
 *
 * These must NOT use `runBlocking`. `writeUtterance` delivers its buffers on the main dispatch
 * queue, and blocking the main thread stops that queue draining — every phrase would come back
 * as a timeout. [awaitPumpingMainLoop] runs the work on a background dispatcher and turns the
 * main run loop while it waits, which is what a real app's run loop does for free.
 */
class IosTtsGeneratorTest {

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val generator = IosTtsGenerator(TestDispatchers)

    @Test
    fun generatesAudibleSamplesForAPhrase() {
        val result = assertNotNull(
            awaitPumpingMainLoop { generator.generate("I'm all out of bubblegum", null, null) },
            "writeUtterance produced no audio",
        )

        assertTrue(result.sampleRate > 8000, "implausible sample rate ${result.sampleRate}")
        val seconds = result.samples.size / result.sampleRate.toFloat()
        assertTrue(seconds > 0.5f, "phrase was only ${seconds}s long")
        assertTrue(result.samples.any { abs(it) > 0.01f }, "clip decoded as silence")
    }

    @Test
    fun reportsAvailableVoices() {
        val voices = awaitPumpingMainLoop { generator.listVoices() }

        assertTrue(voices.isNotEmpty(), "the simulator should ship some voices")
    }

    @Test
    fun aFasterRateProducesAShorterClip() {
        val slow = assertNotNull(awaitPumpingMainLoop { generator.generate("one of these days", null, 120) })
        val fast = assertNotNull(awaitPumpingMainLoop { generator.generate("one of these days", null, 280) })

        assertTrue(
            fast.samples.size < slow.samples.size,
            "rate had no effect: slow=${slow.samples.size} fast=${fast.samples.size}",
        )
    }

    /** Runs [block] on a background dispatcher, turning the main run loop until it finishes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> awaitPumpingMainLoop(timeoutSeconds: Double = 30.0, block: suspend () -> T): T {
        val finished = CompletableDeferred<Result<T>>()
        CoroutineScope(Dispatchers.Default).launch { finished.complete(runCatching { block() }) }

        var waited = 0.0
        while (!finished.isCompleted && waited < timeoutSeconds) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(PUMP_INTERVAL))
            waited += PUMP_INTERVAL
        }
        assertTrue(finished.isCompleted, "timed out after ${timeoutSeconds}s")
        return finished.getCompleted().getOrThrow()
    }

    private companion object {
        const val PUMP_INTERVAL = 0.05
    }
}
