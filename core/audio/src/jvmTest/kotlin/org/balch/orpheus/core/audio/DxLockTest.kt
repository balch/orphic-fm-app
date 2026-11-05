package org.balch.orpheus.core.audio

import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DxLockTest {
    @Test
    fun mapsDuoAndDrumKnobs() {
        assertEquals(VoiceSymbol.duoEngine(2).controlId.key, dxLockEngineControl("duo_harmonics_2"))
        assertEquals(
            VoiceSymbol.duoEngine(5).controlId.key,
            dxLockEngineControl(VoiceSymbol.duoHarmonics(5).controlId.key),
        )
        assertEquals(DrumSymbol.BD_ENGINE.controlId.key, dxLockEngineControl(DrumSymbol.BD_P4.controlId.key))
        assertEquals(DrumSymbol.SD_ENGINE.controlId.key, dxLockEngineControl(DrumSymbol.SD_P4.controlId.key))
        assertEquals(DrumSymbol.HH_ENGINE.controlId.key, dxLockEngineControl(DrumSymbol.HH_P4.controlId.key))
    }

    @Test
    fun onlyTheDrumModKnobsStoreAnEngineOrdinal() {
        assertTrue(isDrumModControl(DrumSymbol.SD_P4.controlId.key))
        assertFalse(isDrumModControl(VoiceSymbol.duoHarmonics(2).controlId.key))
    }

    @Test
    fun otherControlsUnlocked() {
        assertNull(dxLockEngineControl("duo_morph_2"))
        assertNull(dxLockEngineControl("duo_harmonics_9"))
        assertNull(dxLockEngineControl(DrumSymbol.BD_TONE.controlId.key))
        assertNull(dxLockEngineControl(VoiceSymbol.tune(0).controlId.key))
    }
}
