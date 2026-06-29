package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.GenreProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GenreProfileInvariantTest {

    private fun base(matrix: List<Float>?) = GenreProfile(
        swingAmount = 0f,
        ghostProbability = 0f,
        noteRangeLow = 36,
        noteRangeHigh = 72,
        rhythmDensity = 0.5f,
        chordTransitionMatrix = matrix,
    )

    @Test
    fun `chord matrix that is not 49 long throws`() {
        assertFailsWith<IllegalArgumentException> { base(listOf(1f, 2f, 3f)) }
    }

    @Test
    fun `null chord matrix is allowed`() {
        base(null)
    }

    @Test
    fun `49-long chord matrix constructs`() {
        base(List(49) { 0f })
    }
}
