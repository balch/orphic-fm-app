package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BandInvariantTest {

    private val twoMembers = listOf(
        BandMember(name = "A", tracks = listOf(0)),
        BandMember(name = "B", tracks = listOf(1)),
    )

    @Test
    fun `band with wrong-size handoff matrix throws`() {
        // 2 members => matrix must be 4 floats; give it 3
        assertFailsWith<IllegalArgumentException> {
            Band(
                members = twoMembers,
                handoffMatrix = listOf(0f, 0f, 0f),
                pullInMatrix = listOf(0f, 0f, 0f, 0f),
            )
        }
    }

    @Test
    fun `band with correct-size matrices constructs`() {
        Band(
            members = twoMembers,
            handoffMatrix = listOf(0f, 0f, 0f, 0f),
            pullInMatrix = listOf(0f, 0f, 0f, 0f),
        )
    }
}
