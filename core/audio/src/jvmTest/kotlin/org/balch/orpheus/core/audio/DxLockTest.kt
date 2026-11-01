package org.balch.orpheus.core.audio

import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DxLockTest {
    @Test
    fun mapsDuoAndDrumKnobs() {
        assertEquals(VoiceSymbol.duoEngine(2).controlId.key, dxLockEngineControl("duo_harmonics_2"))
        assertEquals(
            VoiceSymbol.duoEngine(5).controlId.key,
            dxLockEngineControl(VoiceSymbol.duoHarmonics(5).controlId.key),
        )
        assertEquals(DrumSymbol.BD_ENGINE.controlId.key, dxLockEngineControl("drums_bd_mod"))
        assertEquals(DrumSymbol.SD_ENGINE.controlId.key, dxLockEngineControl("drums_sd_mod"))
        assertEquals(DrumSymbol.HH_ENGINE.controlId.key, dxLockEngineControl("drums_hh_mod"))
    }

    @Test
    fun otherControlsUnlocked() {
        assertNull(dxLockEngineControl("duo_morph_2"))
        assertNull(dxLockEngineControl("duo_harmonics_9"))
        assertNull(dxLockEngineControl("drums_bd_tone"))
        assertNull(dxLockEngineControl(VoiceSymbol.tune(0).controlId.key))
    }
}
