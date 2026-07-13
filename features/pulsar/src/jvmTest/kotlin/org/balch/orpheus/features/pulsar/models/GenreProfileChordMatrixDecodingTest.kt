package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decode-side leniency for [GenreProfile.chordTransitionMatrix]'s nested-7x7 LLM shorthand.
 * Uses the same Json config as the AI apply path (ignoreUnknownKeys + coerceInputValues)
 * without taking a dependency on features/ai.
 */
class GenreProfileChordMatrixDecodingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun genreJson(matrixJson: String) = """
        {
          "swingAmount": 0.0, "ghostProbability": 0.0,
          "noteRangeLow": 36, "noteRangeHigh": 72, "rhythmDensity": 0.5,
          "chordTransitionMatrix": $matrixJson
        }
    """.trimIndent()

    private fun nestedRows(rows: List<List<Float>>): String =
        rows.joinToString(prefix = "[", postfix = "]") { row -> row.joinToString(prefix = "[", postfix = "]") }

    @Test
    fun `nested 7x7 matrix flattens row-major`() {
        val rows = (0 until 7).map { row -> (0 until 7).map { col -> (row * 7 + col).toFloat() } }
        val profile = json.decodeFromString<GenreProfile>(genreJson(nestedRows(rows)))
        val matrix = requireNotNull(profile.chordTransitionMatrix)
        assertEquals(49, matrix.size)
        assertEquals(rows[1][0], matrix[7], "matrix[7] must be row 1's first value (row-major)")
    }

    @Test
    fun `flat 49 matrix still decodes`() {
        val flat = List(49) { it.toFloat() }
        val profile = json.decodeFromString<GenreProfile>(genreJson(flat.joinToString(prefix = "[", postfix = "]")))
        assertEquals(flat, profile.chordTransitionMatrix)
    }

    @Test
    fun `nested 6x7 fails with the existing 49-values message`() {
        val rows = List(6) { List(7) { 0f } }
        val error = assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<GenreProfile>(genreJson(nestedRows(rows)))
        }
        assertTrue(
            error.message?.contains("must have 49 values") == true,
            "unexpected message: ${error.message}",
        )
    }
}
