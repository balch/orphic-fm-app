package org.balch.orpheus.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmTtsGeneratorTest {

    // Real `say -v ?` lines: names with spaces and parentheses, sometimes one space before the
    // locale, and Siri voices listed twice.
    private val sayOutput = """
        Bad News            en_US    # Hello! My name is Bad News.
        Flo (English (UK))  en_GB    # Hello! My name is Flo.
        Grandma (English (UK)) en_GB    # Hello! My name is Grandma.
        Aman (English (India)) en_IN    # Hello! My name is Aman.
        Aman (English (India)) en_IN    # Hi, I’m Siri!
        Samantha            en_US    # Hello! My name is Samantha.
    """.trimIndent()

    private val table = listOf(
        VoiceAlias(
            name = "Fiona",
            bcp47 = "en-GB",
            macVoices = listOf("Fiona (Enhanced)", "Fiona", "Flo (English (UK))", "Samantha"),
            iosVoices = emptyList(),
            androidVoices = emptyList(),
        ),
    )

    @Test
    fun `parses whole voice names once each`() {
        assertEquals(
            listOf("Bad News", "Flo (English (UK))", "Grandma (English (UK))", "Aman (English (India))", "Samantha"),
            JvmTtsGenerator.parseSayVoices(sayOutput),
        )
    }

    @Test
    fun `an installed voice outside the table is used as named`() {
        assertEquals("Bad News", JvmTtsGenerator.resolveSayVoice("Bad News", listOf("Bad News"), table))
    }

    @Test
    fun `an alias takes its first installed voice`() {
        val installed = listOf("Flo (English (UK))", "Samantha")
        assertEquals("Flo (English (UK))", JvmTtsGenerator.resolveSayVoice("Fiona", installed, table))
    }

    @Test
    fun `an alias list outranks a bare name that is also installed`() {
        val installed = listOf("Fiona", "Fiona (Enhanced)")
        assertEquals("Fiona (Enhanced)", JvmTtsGenerator.resolveSayVoice("Fiona", installed, table))
    }

    @Test
    fun `nothing installed is the system default rather than a name say ignores`() {
        assertNull(JvmTtsGenerator.resolveSayVoice("Fiona", listOf("Daniel"), table))
        assertNull(JvmTtsGenerator.resolveSayVoice("Zarvox", listOf("Daniel"), table))
    }

    @Test
    fun `no voice requested is the system default`() {
        assertNull(JvmTtsGenerator.resolveSayVoice(null, listOf("Samantha"), table))
    }
}
