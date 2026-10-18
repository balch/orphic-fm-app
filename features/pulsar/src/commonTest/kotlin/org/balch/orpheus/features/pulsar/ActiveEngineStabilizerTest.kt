package org.balch.orpheus.features.pulsar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActiveEngineStabilizerTest {
    private val hold = ActiveEngineStabilizer.HOLD_FRAMES

    private fun ids(track4: Int) = IntArray(8) { if (it == 4) track4 else -1 }

    @Test
    fun holdsFor24PollsAndCommitsOnThe25th() {
        val s = ActiveEngineStabilizer()
        repeat(hold - 1) { assertEquals(-1, s.advance(ids(7))[4], "poll ${it + 1}") }
        assertEquals(7, s.advance(ids(7))[4])
    }

    @Test
    fun alternatingIdsNeverCommit() {
        val s = ActiveEngineStabilizer()
        repeat(200) { assertEquals(-1, s.advance(ids(if (it % 2 == 0) 7 else 9))[4]) }
    }

    @Test
    fun aDifferentIdRestartsTheCandidate() {
        val s = ActiveEngineStabilizer()
        repeat(hold - 1) { s.advance(ids(7)) }
        s.advance(ids(9))
        repeat(hold - 1) { assertEquals(-1, s.advance(ids(7))[4]) }
        assertEquals(7, s.advance(ids(7))[4])
    }

    @Test
    fun returnsTheSameArrayUntilACommit() {
        val s = ActiveEngineStabilizer()
        val first = s.advance(ids(-1))
        assertSame(first, s.advance(ids(-1)))
        repeat(hold - 1) { assertSame(first, s.advance(ids(7))) }
        val committed = s.advance(ids(7))
        assertTrue(committed !== first)
        assertSame(committed, s.advance(ids(7)))
    }

    @Test
    fun shortInputCountsAsUnset() {
        val s = ActiveEngineStabilizer()
        repeat(hold) { assertEquals(-1, s.advance(IntArray(0))[4]) }
    }
}
