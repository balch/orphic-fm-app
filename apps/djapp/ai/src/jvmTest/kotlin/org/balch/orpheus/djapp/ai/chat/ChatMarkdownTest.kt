package org.balch.orpheus.djapp.ai.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMarkdownTest {
    private fun AnnotatedString.bold() =
        spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { text.substring(it.start, it.end) }

    private fun AnnotatedString.italic() =
        spanStyles.filter { it.item.fontStyle == FontStyle.Italic }.map { text.substring(it.start, it.end) }

    @Test
    fun boldAndBullets() {
        val blocks = parseChatMarkdown("**Bass** sits lower now:\n- kick at **-2 dB**\n* pad wider\n  - and softer")
        assertEquals(4, blocks.size, "$blocks")
        val head = blocks[0] as MdBlock.Text
        assertEquals("Bass sits lower now:", head.text.text)
        assertEquals(listOf("Bass"), head.text.bold())
        val items = blocks.drop(1).map { it as MdBlock.Item }
        assertEquals(listOf("kick at -2 dB", "pad wider", "and softer"), items.map { it.text.text })
        assertEquals(listOf("•", "•", "•"), items.map { it.marker })
        assertEquals(listOf(0, 0, 1), items.map { it.level })
        assertEquals(listOf("-2 dB"), items[0].text.bold())
    }

    @Test
    fun numberedList() {
        val blocks = parseChatMarkdown("Try one:\n\n1. drop the pad\n2) *slower* hats\n10. done")
        assertEquals(MdBlock.Gap, blocks[1])
        val items = blocks.filterIsInstance<MdBlock.Item>()
        assertEquals(listOf("1.", "2.", "10."), items.map { it.marker })
        assertEquals(listOf("drop the pad", "slower hats", "done"), items.map { it.text.text })
        assertEquals(listOf("slower"), items[1].text.italic())
    }

    @Test
    fun literalAsterisksInsideWordsStay() {
        val src = "5*3*2 beats, snake_case_name, a * lone star and 2**8"
        val blocks = parseChatMarkdown(src)
        val only = blocks.single() as MdBlock.Text
        assertEquals(src, only.text.text)
        assertTrue(only.text.spanStyles.isEmpty(), "${only.text.spanStyles}")
    }

    @Test
    fun plainLinesKeepTheirBreaks() {
        val blocks = parseChatMarkdown("### Mix notes\nfirst line\nsecond line\n\n\n\nafter")
        assertEquals(4, blocks.size, "$blocks")
        assertEquals(listOf("Mix notes"), (blocks[0] as MdBlock.Text).text.bold())
        assertEquals("first line\nsecond line", (blocks[1] as MdBlock.Text).text.text)
        assertEquals(MdBlock.Gap, blocks[2])
        assertEquals("after", (blocks[3] as MdBlock.Text).text.text)
    }
}
