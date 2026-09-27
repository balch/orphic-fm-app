package org.balch.orpheus.core.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.AudioHostSuspender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AudioHostSuspendPolicyTest {

    @Test
    fun `paused and backgrounded suspends once at the grace boundary`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(30.seconds - 1.milliseconds)
        runCurrent()
        assertEquals(0, suspender.suspendResults.size, "must not suspend before grace elapses")

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, suspender.suspendResults.size, "must attempt exactly once at the grace boundary")
        assertEquals(1, suspender.parks, "the first attempt parks")

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, suspender.suspendResults.size, "the attempt repeats every grace period")
        assertEquals(1, suspender.parks, "a repeat on a parked host does not park again")

        job.cancel()
    }

    @Test
    fun `play during grace cancels the pending suspend`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(10.seconds)
        runCurrent()
        playback.value = PlaybackState.Playing
        runCurrent()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(0, suspender.suspendResults.size, "resuming playback must cancel the grace delay")

        job.cancel()
    }

    @Test
    fun `foreground during grace cancels the pending suspend and resumes`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(10.seconds)
        runCurrent()
        foreground.value = true
        runCurrent()
        assertEquals(1, suspender.resumeCalls, "coming to foreground must resume the host")

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(0, suspender.suspendResults.size, "the earlier grace delay must have been cancelled")

        job.cancel()
    }

    @Test
    fun `playing in background never suspends`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Playing)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(0, suspender.suspendResults.size, "playing audio must never be suspended")

        job.cancel()
    }

    @Test
    fun `a predicate that turns false while the park runs is reported false`() = runTest {
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        // Simulates a race: playback resumes between the grace delay firing and the
        // park actually evaluating stillWanted().
        val suspender = FakeSuspender(onSuspend = { playback.value = PlaybackState.Playing })
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(listOf(false), suspender.suspendResults, "the predicate must see the raced-in Playing state")

        job.cancel()
    }

    @Test
    fun `rapid toggles within grace produce zero suspends, one final background produces one`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        repeat(5) {
            advanceTimeBy(5.seconds)
            runCurrent()
            foreground.value = true
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            foreground.value = false
            runCurrent()
        }
        assertEquals(0, suspender.suspendResults.size, "no single leg reached the grace period")

        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(1, suspender.suspendResults.size, "the final, untouched background leg suspends once")
        assertEquals(1, suspender.parks)

        job.cancel()
    }

    @Test
    fun `a vetoed first attempt parks on the next grace period`() = runTest {
        // Mirrors DspSynthEngine folding TTS into stillWanted: speech at 30s vetoes the park.
        var speaking = true
        val suspender = FakeSuspender(veto = { speaking })
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(false), suspender.suspendResults, "the first attempt is vetoed")
        assertEquals(0, suspender.parks)

        speaking = false
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(false, true), suspender.suspendResults, "a second attempt follows a grace period later")
        assertEquals(1, suspender.parks, "and parks")

        job.cancel()
    }

    @Test
    fun `a host resumed behind the policy's back is parked again`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Paused)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(1, suspender.parks)

        // TTS resumes the parked host without any playback or foreground change.
        suspender.resumeExternally()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, suspender.parks, "the next attempt parks the resumed host")

        job.cancel()
    }

    @Test
    fun `stopped in the background parks after grace`() = runTest {
        val suspender = FakeSuspender()
        // Stopped is PlaybackController's initial state, e.g. a widget-intent background launch.
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
        val foreground = MutableStateFlow(false)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(true), suspender.suspendResults)
        assertEquals(1, suspender.parks)

        job.cancel()
    }

    @Test
    fun `a foreground-seeded start never parks`() = runTest {
        val suspender = FakeSuspender()
        val playback = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
        val foreground = MutableStateFlow(true)
        val job = AudioHostSuspendPolicy(suspender, this).start(playback, foreground)
        runCurrent()

        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(0, suspender.suspendResults.size, "an on-screen app must never be parked")
        assertEquals(1, suspender.resumeCalls, "the foreground seed asks for a resume")

        job.cancel()
    }
}

/**
 * Records every attempt's predicate result, and models the host: a wanted attempt parks,
 * one on a parked host is a no-op, and [veto] stands in for DspSynthEngine's TTS check.
 */
private class FakeSuspender(
    private val onSuspend: () -> Unit = {},
    private val veto: () -> Boolean = { false },
) : AudioHostSuspender {
    val suspendResults = mutableListOf<Boolean>()
    var parks = 0
        private set
    var resumeCalls = 0
        private set
    private var parked = false

    override fun suspendHost(stillWanted: () -> Boolean) {
        onSuspend()
        val wanted = stillWanted() && !veto()
        suspendResults.add(wanted)
        if (wanted && !parked) {
            parked = true
            parks++
        }
    }

    override fun resumeHostIfIdle() {
        resumeCalls++
        parked = false
    }

    fun resumeExternally() {
        parked = false
    }
}
