package org.balch.orpheus.features.pulsar.mixer

import kotlin.test.Test
import kotlin.test.assertEquals

class MixerMathTest {

    @Test
    fun `groupMuted is true only when all group tracks are muted`() {
        val allMuted = listOf(true, true, true, false, false, true, true, true)
        assertEquals(true, isGroupMuted(MixerGroup.PERC, allMuted))   // 0,1,2 all muted
        assertEquals(false, isGroupMuted(MixerGroup.BASS, allMuted))  // 3 not muted
        assertEquals(false, isGroupMuted(MixerGroup.KEYS, allMuted))  // 4 not muted
        assertEquals(true, isGroupMuted(MixerGroup.FX, allMuted))     // 5,6,7 all muted
    }

    @Test
    fun `groupMuted handles short mute list defensively`() {
        // PulsarFeature returns List(8); test that we cope if the list is shorter.
        assertEquals(false, isGroupMuted(MixerGroup.FX, listOf(true, true)))
    }
}
