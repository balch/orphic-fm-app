package org.balch.orpheus.djapp.ai.ideas

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmIdeasGeneratorTest {

    @Test
    fun parsesFencedJson() {
        val raw = """
            Here you go:
            ```json
            [
              {"text": "Bring the pad in", "prompt": "Bring the pad in for the next section", "mix": false},
              {"text": "Pull the hats back", "prompt": "Drop the hats 3 dB", "mix": true}
            ]
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                Idea("Bring the pad in", "Bring the pad in for the next section", mix = false),
                Idea("Pull the hats back", "Drop the hats 3 dB", mix = true),
            ),
            parseIdeas(raw, mixerOpen = true),
        )
    }

    @Test
    fun dropsMixIdeasWhenMixerClosed() {
        val raw = """
            [
              {"text": "Add a bridge", "prompt": "Add a bridge before the last chorus", "mix": false},
              {"text": "Wider pads", "prompt": "Pan the pads wider", "mix": true},
              {"text": "Brighter lead", "prompt": "Give the lead a brighter voice"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                Idea("Add a bridge", "Add a bridge before the last chorus", mix = false),
                Idea("Brighter lead", "Give the lead a brighter voice", mix = false),
            ),
            parseIdeas(raw, mixerOpen = false),
        )
    }

    @Test
    fun preambleWithBracketsStillFindsTheArray() {
        val raw = """
            Reading [Mixer] open and the [Mix] lines, here are ideas:
            ```json
            [{"text": "Lift the keys", "prompt": "Bring the keys up 2 dB", "mix": true}]
            ```
            Hope that helps [smile].
        """.trimIndent()

        assertEquals(listOf(Idea("Lift the keys", "Bring the keys up 2 dB", mix = true)), parseIdeas(raw, mixerOpen = true))
    }

    @Test
    fun keepsAtMostThreeIdeas() {
        val raw = (1..5).joinToString(",", "[", "]") { """{"text": "idea $it", "prompt": "do $it"}""" }
        assertEquals(listOf("idea 1", "idea 2", "idea 3"), parseIdeas(raw, mixerOpen = true).map { it.text })
    }

    @Test
    fun unparseableReplyIsEmpty() {
        assertEquals(emptyList(), parseIdeas("Sorry, I can't help with that.", mixerOpen = true))
        assertEquals(emptyList(), parseIdeas("[{\"text\": 3", mixerOpen = true))
    }
}
