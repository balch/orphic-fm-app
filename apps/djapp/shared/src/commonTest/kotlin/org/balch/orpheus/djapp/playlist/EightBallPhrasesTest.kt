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

    // The phrase hovers on one line over the stage, shrunk to fit a phone's width.
    @Test
    fun everyPhraseFitsOneLine() = EightBallPhrases.forEach { assertTrue(it.length <= 42, "\"$it\" is too long for one line") }
}
