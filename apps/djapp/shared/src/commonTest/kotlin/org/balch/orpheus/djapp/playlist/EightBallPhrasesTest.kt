package org.balch.orpheus.djapp.playlist

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EightBallPhrasesTest {
    @Test
    fun neverTheSamePhraseTwiceRunning() {
        val phrases = listOf("a", "b", "c")
        var last: String? = null
        repeat(200) { seed ->
            val next = nextPhrase(last, phrases, Random(seed))
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun aLonePhraseRepeats() = assertEquals("a", nextPhrase("a", listOf("a")))

    // The die fits about three lines of eight capitals at the reveal's 160dp.
    @Test
    fun everyPhraseFitsTheDie() = EightBallPhrases.forEach { assertTrue(it.length <= 24, "\"$it\" is too long for the die") }
}
