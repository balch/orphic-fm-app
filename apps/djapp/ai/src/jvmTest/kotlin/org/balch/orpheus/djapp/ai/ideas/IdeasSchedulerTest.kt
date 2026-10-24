package org.balch.orpheus.djapp.ai.ideas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class IdeasSchedulerTest {

    private fun input(tag: String) = IdeasInput(
        mixerOpen = true,
        vibeSummary = tag,
        mixSnapshot = "",
        recentMoves = emptyList(),
        recentChat = emptyList(),
    )

    private fun ideas(vararg texts: String) = texts.map { Idea(it, it, mix = false) }

    /** Records each call's input; [reply] decides what a call returns, and may suspend. */
    private class FakeGenerator(var reply: suspend (IdeasInput) -> List<Idea>) : IdeasGenerator {
        val calls = mutableListOf<IdeasInput>()
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun ideas(input: IdeasInput): List<Idea> {
            calls += input
            maxInFlight = maxOf(maxInFlight, ++inFlight)
            try {
                return reply(input)
            } finally {
                inFlight--
            }
        }
    }

    private fun TestScope.scheduler(generator: IdeasGenerator) =
        IdeasScheduler(backgroundScope, generator, clock = { testScheduler.currentTime })

    @Test
    fun refreshesAfterTurn() = runTest {
        val gen = FakeGenerator { ideas("a", "b") }
        val s = scheduler(gen)

        s.onTurnStarted()
        s.onTurnEnded(input("turn"))
        runCurrent()

        assertEquals(listOf("turn"), gen.calls.map { it.vibeSummary })
        assertEquals(ideas("a", "b"), s.ideas.value)
    }

    @Test
    fun refreshesFourSecondsAfterLastMove() = runTest {
        val gen = FakeGenerator { ideas("x") }
        val s = scheduler(gen)

        s.onUserMove { input("move") }
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(0, gen.calls.size, "no refresh before the pause")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("move"), gen.calls.map { it.vibeSummary })
        assertEquals(ideas("x"), s.ideas.value)

        advanceTimeBy(20_000)
        assertEquals(1, gen.calls.size, "one refresh per pause")
    }

    @Test
    fun movesResetThePause() = runTest {
        val gen = FakeGenerator { ideas("x") }
        val s = scheduler(gen)

        s.onUserMove { input("first") }
        advanceTimeBy(3_000)
        s.onUserMove { input("second") }
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(0, gen.calls.size, "the second move restarts the 4 s")

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("second"), gen.calls.map { it.vibeSummary }, "one refresh, with the latest input")
    }

    @Test
    fun noRefreshDuringTurn() = runTest {
        val gen = FakeGenerator { ideas("x") }
        val s = scheduler(gen)

        // A pending pause refresh is dropped when a turn starts.
        s.onUserMove { input("before") }
        advanceTimeBy(2_000)
        s.onTurnStarted()
        advanceTimeBy(10_000)
        // Moves while the turn runs wait for its end.
        s.onUserMove { input("during") }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, gen.calls.size)

        s.onTurnEnded(input("end"))
        runCurrent()
        assertEquals(listOf("end"), gen.calls.map { it.vibeSummary })
    }

    @Test
    fun oneCallInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gen = FakeGenerator { gate.await(); ideas(it.vibeSummary) }
        val s = scheduler(gen)

        s.onTurnEnded(input("a"))
        runCurrent()
        s.onTurnEnded(input("b"))
        s.onUserMove { input("c") }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, gen.calls.size, "triggers wait for the call in flight")

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "c"), gen.calls.map { it.vibeSummary }, "one follow-up, with the latest input")
        assertEquals(1, gen.maxInFlight)
        assertEquals(ideas("c"), s.ideas.value)

        advanceTimeBy(20_000)
        assertEquals(2, gen.calls.size)
    }

    @Test
    fun failureKeepsPreviousIdeas() = runTest {
        val gen = FakeGenerator { ideas("keep") }
        val s = scheduler(gen)
        s.onTurnEnded(input("ok"))
        runCurrent()
        assertEquals(ideas("keep"), s.ideas.value)

        gen.reply = { error("network down") }
        s.onTurnEnded(input("throws"))
        runCurrent()
        assertEquals(ideas("keep"), s.ideas.value)

        gen.reply = { emptyList() }
        s.onTurnEnded(input("empty"))
        runCurrent()
        assertEquals(ideas("keep"), s.ideas.value)
        assertEquals(3, gen.calls.size)

        // A failed call does not wedge the scheduler.
        gen.reply = { ideas("new") }
        s.onTurnEnded(input("again"))
        runCurrent()
        assertEquals(ideas("new"), s.ideas.value)
    }
}
