package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.core.controller.SynthController
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTrackInputTest {
    private val writes = mutableListOf<Pair<String, Float>>()
    private val input = LiveTrackInput(
        SynthController().apply {
            setDelegates(setter = { id, v -> writes += id.symbol to v.asFloat(); true }, getter = { null })
        },
    )

    @Test
    fun `boost writes the track's boost port`() {
        input.boost(4, 1.585f)
        assertEquals(listOf("track_4_live_boost" to 1.585f), writes)
    }

    @Test
    fun `disarm releases the gate and puts the boost back to 1`() {
        input.boost(4, 2f)
        writes.clear()
        input.disarm(4)
        assertEquals(
            listOf("track_4_live_gate" to 0f, "track_4_live_active" to 0f, "track_4_live_boost" to 1f),
            writes,
        )
    }
}
