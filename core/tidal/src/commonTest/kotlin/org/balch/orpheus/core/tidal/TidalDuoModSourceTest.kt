package org.balch.orpheus.core.tidal

import org.balch.orpheus.core.audio.patch.LegacyModSource
import kotlin.test.Test
import kotlin.test.assertEquals

class TidalDuoModSourceTest {
    @Test
    fun namesMapToLegacySources() {
        assertEquals(LegacyModSource.VOICE_FM, tidalDuoModSource("fm"))
        assertEquals(LegacyModSource.VOICE_FM, tidalDuoModSource("FM"))
        assertEquals(LegacyModSource.LFO, tidalDuoModSource("lfo"))
        assertEquals(LegacyModSource.OFF, tidalDuoModSource("off"))
        assertEquals(LegacyModSource.OFF, tidalDuoModSource("flux"))
    }
}
