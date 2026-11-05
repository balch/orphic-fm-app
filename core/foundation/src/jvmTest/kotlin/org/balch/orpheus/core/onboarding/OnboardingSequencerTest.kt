package org.balch.orpheus.core.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ./gradlew :core:foundation:jvmTest --tests '*OnboardingSequencerTest*' */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSequencerTest {
    private class Memory(var prefs: AppPreferences = AppPreferences()) : BaseAppPreferencesRepository() {
        override suspend fun load() = prefs
        override suspend fun save(preferences: AppPreferences) {
            prefs = preferences
        }
    }

    private val first = OnboardingStep("first", holdMillis = 1_000L)
    private val second = OnboardingStep("second")

    private fun TestScope.launchOf(memory: Memory, vararg queue: OnboardingStep) =
        OnboardingSequencer(queue.toList(), memory, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `a capped step shows on each launch until its cap and passes its count`() = runTest {
        val memory = Memory()
        val step = OnboardingStep("capped", maxShows = 2)
        val seen = List(3) {
            var shows: Int? = null
            launchOf(memory, step).run(step) { shows = it }
            shows
        }
        assertEquals(listOf(0, 1, null), seen)
        assertEquals(2, memory.prefs.onboarding["capped"]?.shows)
    }

    @Test
    fun `a step runs once a launch whoever asks`() = runTest {
        val sequencer = launchOf(Memory(), first)
        assertTrue(sequencer.run(first) {})
        assertFalse(sequencer.run(first) {}, "a second bar showed it again")
        assertNull(sequencer.awaitTurn(first), "a recreated activity owed it again")
    }

    @Test
    fun `a learned step is gone now and on later launches`() = runTest {
        val memory = Memory()
        val sequencer = launchOf(memory, first)
        sequencer.learn(first)
        assertNull(sequencer.awaitTurn(first))
        assertFalse(sequencer.run(first) {})
        assertEquals(true, memory.prefs.onboarding["first"]?.learned)
        assertNull(launchOf(memory, first).awaitTurn(first), "the next launch showed it")
    }

    @Test
    fun `an answer saved before the step existed counts as learned`() = runTest {
        val step = OnboardingStep("old", learnedAlready = { it.domeSwiped })
        val memory = Memory(AppPreferences(domeSwiped = true))
        assertNull(launchOf(memory, step).awaitTurn(step))
    }

    @Test
    fun `a later step waits for the running step to end`() = runTest(UnconfinedTestDispatcher()) {
        val sequencer = launchOf(Memory(), first, second)
        val release = CompletableDeferred<Unit>()
        launch { sequencer.run(first) { release.await() } }
        val turn = async { sequencer.awaitTurn(second) }
        advanceTimeBy(60_000L)
        assertFalse(turn.isCompleted, "it went over the running step")
        release.complete(Unit)
        assertEquals(0, turn.await())
    }

    @Test
    fun `a step nobody begins gives its launch up after its hold without counting`() = runTest {
        val memory = Memory()
        val sequencer = launchOf(memory, first, second)
        val turn = async { sequencer.awaitTurn(second) }
        runCurrent()
        advanceTimeBy(999L)
        assertFalse(turn.isCompleted, "it stopped waiting before the hold")
        advanceTimeBy(2L)
        assertEquals(0, turn.await())
        assertFalse(sequencer.run(first) {}, "the step ran after giving its launch up")
        assertNull(memory.prefs.onboarding["first"], "a step that never showed was counted")
    }

    @Test
    fun `a skipped step frees the next at once and is not counted`() = runTest {
        val memory = Memory()
        val sequencer = launchOf(memory, first, second)
        sequencer.skip(first)
        assertEquals(0, sequencer.awaitTurn(second))
        assertNull(memory.prefs.onboarding["first"])
        assertEquals(0, launchOf(memory, first).awaitTurn(first), "a skip cost it a later launch")
    }

    @Test
    fun `a step already spent does not hold the next`() = runTest {
        val memory = Memory()
        val spent = OnboardingStep("spent", maxShows = 1, holdMillis = 60_000L)
        launchOf(memory, spent).run(spent) {}
        assertEquals(0, launchOf(memory, spent, second).awaitTurn(second))
        assertEquals(0L, testScheduler.currentTime, "it waited out a step with nothing to show")
    }

    @Test
    fun `a step that owns its launch keeps the next off it at once and only that launch`() = runTest(UnconfinedTestDispatcher()) {
        val memory = Memory()
        val owner = OnboardingStep("owner", maxShows = 1, ownsLaunch = true)
        val sequencer = launchOf(memory, owner, second)
        val release = CompletableDeferred<Unit>()
        val turn = async { sequencer.awaitTurn(second) }
        launch { sequencer.run(owner) { release.await() } }
        assertNull(turn.await(), "the next step waited or went while the owner ran")
        assertFalse(sequencer.run(second) {}, "the next step ran on the owner's launch")
        release.complete(Unit)
        assertNull(sequencer.awaitTurn(second), "the owner's end freed the launch")
        assertEquals(0, launchOf(memory, owner, second).awaitTurn(second), "the next launch stayed taken")
    }

    @Test
    fun `an owner that shows nothing leaves the launch free`() = runTest {
        val owner = OnboardingStep("owner", ownsLaunch = true)
        val skipped = launchOf(Memory(), owner, second)
        skipped.skip(owner)
        assertEquals(0, skipped.awaitTurn(second))

        val learned = launchOf(Memory(), owner, second)
        learned.learn(owner)
        assertEquals(0, learned.awaitTurn(second))

        val lapsed = launchOf(Memory(), owner, second)
        val turn = async { lapsed.awaitTurn(second) }
        runCurrent()
        advanceTimeBy(owner.holdMillis + 1)
        assertEquals(0, turn.await(), "an owner nobody began kept the launch")
    }

    @Test
    fun `a cancelled step still lets the next go`() = runTest(UnconfinedTestDispatcher()) {
        val sequencer = launchOf(Memory(), first, second)
        val running = launch { sequencer.run(first) { CompletableDeferred<Unit>().await() } }
        val turn = async { sequencer.awaitTurn(second) }
        running.cancelAndJoin()
        assertEquals(0, turn.await())
    }

    @Test
    fun `a hint outside the queue is pending until learned`() = runTest {
        val memory = Memory()
        val hint = OnboardingStep("hint")
        val sequencer = launchOf(memory, first)
        assertTrue(sequencer.pending(hint).first())
        sequencer.learn(hint)
        assertFalse(sequencer.pending(hint).first())
        assertFalse(launchOf(memory, first).pending(hint).first(), "the next launch forgot it")
    }

    @Test
    fun `a hint outside the queue waits for nothing`() = runTest {
        val sequencer = launchOf(Memory(), first)
        val hint = OnboardingStep("hint")
        assertEquals(0, sequencer.awaitTurn(hint))
        assertEquals(0L, testScheduler.currentTime)
    }
}
