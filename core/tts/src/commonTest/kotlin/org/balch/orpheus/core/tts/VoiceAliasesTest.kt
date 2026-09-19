package org.balch.orpheus.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceAliasesTest {

    private val table = listOf(
        VoiceAlias(
            name = "Daniel",
            bcp47 = "en-GB",
            macVoices = listOf("Daniel"),
            iosVoices = listOf("com.apple.voice.compact.en-GB.Daniel"),
            androidVoices = listOf("en-gb-x-rjs-local"),
        ),
        VoiceAlias(
            name = "Samantha",
            bcp47 = "en-US",
            macVoices = emptyList(),
            iosVoices = emptyList(),
            androidVoices = emptyList(),
        ),
    )

    @Test
    fun `resolves a voice by name`() {
        val alias = assertNotNull(VoiceAliases.resolve("Daniel", table))

        assertEquals("en-GB", alias.bcp47)
        assertEquals(listOf("com.apple.voice.compact.en-GB.Daniel"), alias.iosVoices)
    }

    @Test
    fun `resolves ignoring case`() {
        assertEquals("Samantha", VoiceAliases.resolve("samantha", table)?.name)
    }

    @Test
    fun `returns null when the vibe names no voice`() {
        assertNull(VoiceAliases.resolve(null, table))
    }

    @Test
    fun `returns null for a voice outside the table`() {
        assertNull(VoiceAliases.resolve("Zarvox", table))
    }

    @Test
    fun `returns null against an empty table so the platform default is used`() {
        assertNull(VoiceAliases.resolve("Daniel", emptyList()))
    }

    @Test
    fun `unspecified rate is the platform default`() {
        assertEquals(1f, speechRateScale(null), 1e-4f)
    }

    @Test
    fun `the reference rate is the platform default`() {
        assertEquals(1f, speechRateScale(REFERENCE_WPM), 1e-4f)
    }

    @Test
    fun `double the reference rate is twice as fast`() {
        assertEquals(2f, speechRateScale(REFERENCE_WPM * 2), 1e-4f)
    }

    @Test
    fun `a nonsense rate never collapses to silence`() {
        // Android ignores a rate of 0, which would silently hand back the default instead.
        assertTrue(speechRateScale(0) > 0f)
        assertTrue(speechRateScale(-50) > 0f)
        assertTrue(speechRateScale(100_000) < 10f)
    }
}
