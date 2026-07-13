package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decode-side leniency for [ChordStep]'s bare-number LLM shorthand (`3` == `ChordStep(3)`).
 * Uses the same Json config as the AI apply path (ignoreUnknownKeys + coerceInputValues)
 * without taking a dependency on features/ai.
 */
class ChordStepDecodingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `bare int list decodes to ChordSteps with zero glide`() {
        val steps = json.decodeFromString<List<ChordStep>>("[0,3,5,6]")
        assertEquals(listOf(0, 3, 5, 6), steps.map { it.degree })
        assertTrue(steps.all { it.glideRate == 0f }, "bare-number shorthand must default glideRate to 0")
    }

    @Test
    fun `mixed bare int and object form decodes`() {
        val steps = json.decodeFromString<List<ChordStep>>(
            """[0, {"degree":3,"glideRate":0.4}]""",
        )
        assertEquals(listOf(ChordStep(0), ChordStep(3, glideRate = 0.4f)), steps)
    }

    @Test
    fun `pure object form still decodes`() {
        val steps = json.decodeFromString<List<ChordStep>>(
            """[{"degree":1,"glideRate":0.2},{"degree":4,"glideRate":0.0}]""",
        )
        assertEquals(listOf(ChordStep(1, glideRate = 0.2f), ChordStep(4)), steps)
    }

    @Test
    fun `encoding a decoded bare-int list re-emits the object form`() {
        val steps = json.decodeFromString<List<ChordStep>>("[0,3,5,6]")
        val reEncoded = json.encodeToString(steps)
        assertTrue(reEncoded.contains("\"degree\""), "re-encoded JSON dropped the object form: $reEncoded")
    }

    @Test
    fun `bare-int degree out of range still fails GenreProfile validation`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<GenreProfile>(
                """
                {
                  "swingAmount": 0.0, "ghostProbability": 0.0,
                  "noteRangeLow": 36, "noteRangeHigh": 72, "rhythmDensity": 0.5,
                  "customProgression": [9]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `Section customProgression accepts bare-int shorthand`() {
        val section = json.decodeFromString<Section>(
            """{"name":"drop","customProgression":[0,3,5,6]}""",
        )
        assertEquals(listOf(0, 3, 5, 6), section.customProgression?.map { it.degree })
    }
}
