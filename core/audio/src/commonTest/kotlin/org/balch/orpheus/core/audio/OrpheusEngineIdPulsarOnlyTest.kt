package org.balch.orpheus.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class OrpheusEngineIdPulsarOnlyTest {

    @Test
    fun `panel-playable engines are a prefix of entries`() {
        val panelPlayable = OrpheusEngineId.entries.filterNot { it.isPulsarOnly }
        assertEquals(
            OrpheusEngineId.entries.take(panelPlayable.size),
            panelPlayable,
            "Drum ports store entry ordinals, so a Pulsar-only engine must be appended " +
                "after every panel-playable one",
        )
    }
}
