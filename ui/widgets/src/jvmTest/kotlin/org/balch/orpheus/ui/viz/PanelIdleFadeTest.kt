package org.balch.orpheus.ui.viz

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fade is three seconds of waiting followed by six tenths of animation, so every test here
 * runs on `runTest`'s virtual clock. [frameClock] is what lets `Animatable` advance on it too:
 * without a frame clock in the context, `animateTo` would hang forever.
 */
class PanelIdleFadeTest {

    @Test
    fun `the panels stay up for the idle timeout and are gone once the fade ends`() = runTest {
        val fade = PanelIdleFade { currentTime }
        val job = launch(frameClock()) { fade.runIdleCycle(running = true) }

        advanceTimeBy(PanelIdleTimeoutMs - 100)
        assertEquals(1f, fade.alpha.value, "panels moved before the timeout")

        advanceTimeBy(100L + PanelFadeOutMs / 2)
        assertTrue(
            fade.alpha.value < 1f && fade.alpha.value > 0f,
            "panels are not part way through the fade: ${fade.alpha.value}",
        )

        advanceTimeBy(PanelFadeOutMs.toLong())
        assertEquals(0f, fade.alpha.value, LANDED, "panels never finished fading")
        job.cancel()
    }

    @Test
    fun `input part way through the fade brings the panels back`() = runTest {
        val fade = PanelIdleFade { currentTime }
        var job = launch(frameClock()) { fade.runIdleCycle(running = true) }
        advanceTimeBy(PanelIdleTimeoutMs + PanelFadeOutMs / 2)
        assertTrue(fade.alpha.value < 1f, "the fade never started")

        // What the watcher does on a new activityTick: cancel the cycle and start a fresh one.
        job.cancel()
        fade.notifyActivity()
        job = launch(frameClock()) { fade.runIdleCycle(running = true) }

        advanceTimeBy(PanelFadeInMs.toLong() + FRAME_MS)
        assertEquals(1f, fade.alpha.value, LANDED, "input did not bring the panels back")
        job.cancel()
    }

    @Test
    fun `a disabled fade pins the panels at full strength`() = runTest {
        val fade = PanelIdleFade { currentTime }
        val faded = launch(frameClock()) { fade.runIdleCycle(running = true) }
        advanceTimeBy(PanelIdleTimeoutMs + PanelFadeOutMs + FRAME_MS)
        assertEquals(0f, fade.alpha.value, LANDED, "the panels never faded out")
        faded.cancel()

        val off: Job = launch(frameClock()) { fade.runIdleCycle(running = false) }
        advanceTimeBy(PanelIdleTimeoutMs * 3)
        assertEquals(1f, fade.alpha.value, "a disabled fade did not restore the panels")
        assertTrue(off.isCompleted, "a disabled fade left a coroutine running")
    }

    @Test
    fun `an open modal holds the panels up for as long as it is open`() = runTest {
        val fade = PanelIdleFade { currentTime }
        fade.openModal()
        assertEquals(1, fade.modalCount)
        fade.closeModal()
        assertEquals(0, fade.modalCount)
        // Never negative, whatever order dropdowns and sheets happen to dispose in.
        fade.closeModal()
        assertEquals(0, fade.modalCount)
    }

    @Test
    fun `a drag's own reports do not rebuild the coroutine but still move the deadline`() = runTest {
        val fade = PanelIdleFade { currentTime }
        fade.notifyActivity()
        val first = fade.activityTick

        // A held drag reports this fast; the watcher should not be torn down and rebuilt for
        // each one. Every one of them must still count, which is what quietMs proves.
        repeat(20) {
            advanceTimeBy(8)
            fade.notifyActivity()
        }
        assertEquals(first, fade.activityTick, "a throttled report still rebuilt the coroutine")
        assertEquals(0L, fade.quietMs(), "a throttled report was dropped instead of recorded")

        advanceTimeBy(PanelActivityThrottleMs)
        fade.notifyActivity()
        assertEquals(first + 1, fade.activityTick, "input past the throttle window was dropped")
    }

    @Test
    fun `input at two point nine seconds moves the fade to five point nine`() = runTest {
        val fade = PanelIdleFade { currentTime }
        val watcher = Watcher(this, fade)

        advanceTimeBy(PanelIdleTimeoutMs - 100)
        assertEquals(1f, fade.alpha.value, "the panels moved before the first deadline")

        fade.notifyActivity()
        watcher.sync()

        // The new deadline is 5900. At 5800 nothing has happened yet.
        advanceTimeBy(PanelIdleTimeoutMs - 100)
        assertEquals(5_800L, currentTime)
        assertEquals(1f, fade.alpha.value, "the input at 2.9s did not move the deadline")

        advanceTimeBy(100L + PanelFadeOutMs / 2)
        assertTrue(fade.alpha.value < 1f, "the fade never started after 5.9s: ${fade.alpha.value}")
        watcher.cancel()
    }

    @Test
    fun `a held drag keeps the panels up throughout and the countdown starts at the release`() = runTest {
        val fade = PanelIdleFade { currentTime }
        val watcher = Watcher(this, fade)

        // Five seconds of a knob drag: one report per frame while the button is down.
        repeat((5_000L / FRAME_MS).toInt()) {
            advanceTimeBy(FRAME_MS)
            fade.notifyActivity()
            watcher.sync()
            assertEquals(1f, fade.alpha.value, "the panels moved mid-drag at ${currentTime}ms")
        }

        fade.notifyActivity()
        watcher.sync()
        val release = currentTime

        advanceTimeBy(PanelIdleTimeoutMs - 100)
        assertEquals(1f, fade.alpha.value, "the fade started before three seconds past the release")

        advanceTimeBy(100L + PanelFadeOutMs / 2)
        assertTrue(fade.alpha.value < 1f, "the fade never started after the release")
        assertTrue(
            currentTime - release >= PanelIdleTimeoutMs,
            "the countdown did not run from the release",
        )
        watcher.cancel()
    }

    @Test
    fun `the throttle never delays waking panels that are already going`() = runTest {
        val fade = PanelIdleFade { currentTime }
        val job = launch(frameClock()) { fade.runIdleCycle(running = true) }
        advanceTimeBy(PanelIdleTimeoutMs + FRAME_MS)
        job.cancel()

        val before = fade.activityTick
        fade.notifyActivity()
        fade.notifyActivity()
        assertEquals(before + 2, fade.activityTick, "a fading panel throttled its own wake-up")
    }

    /**
     * Stands in for [PanelIdleFadeWatcher]: a new [PanelIdleFade.activityTick] cancels the cycle
     * and starts a fresh one, which is the only thing the composable does that matters to timing.
     */
    private class Watcher(private val scope: TestScope, private val fade: PanelIdleFade) {
        private val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(FRAME_MS)
                return onFrame(scope.currentTime * 1_000_000L)
            }
        }
        private var seen = fade.activityTick
        private var job: Job = scope.launch(clock) { fade.runIdleCycle(running = true) }

        fun sync() {
            if (fade.activityTick == seen) return
            seen = fade.activityTick
            job.cancel()
            job = scope.launch(clock) { fade.runIdleCycle(running = true) }
        }

        fun cancel() {
            job.cancel()
        }
    }

    /** Virtual frames, so `Animatable` advances with `advanceTimeBy` instead of wall time. */
    private fun TestScope.frameClock(): MonotonicFrameClock = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(FRAME_MS)
            return onFrame(currentTime * 1_000_000L)
        }
    }

    private companion object {
        const val FRAME_MS = 16L

        /**
         * advanceTimeBy stops short of the instant it names, so the animation's very last frame
         * may not have run; a tenth of a percent is landed for a value the eye reads as alpha.
         */
        const val LANDED = 0.001f
    }
}
