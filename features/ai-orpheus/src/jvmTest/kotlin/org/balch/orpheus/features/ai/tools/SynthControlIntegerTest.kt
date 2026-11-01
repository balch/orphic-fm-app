package org.balch.orpheus.features.ai.tools

import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SynthControlIntegerTest {

    private val resonatorMode = ResonatorSymbol.MODE.controlId

    @Test
    fun resonatorModeReachesAllThreeEngineModes() {
        // 0 = Modal, 1 = Sympathetic, 2 = String
        assertEquals(0, integerControlValue(resonatorMode, 0f))
        assertEquals(1, integerControlValue(resonatorMode, 1f))
        assertEquals(2, integerControlValue(resonatorMode, 2f))
    }

    @Test
    fun resonatorModeRoundsAndClamps() {
        assertEquals(2, integerControlValue(resonatorMode, 1.6f))
        assertEquals(2, integerControlValue(resonatorMode, 9f))
        assertEquals(0, integerControlValue(resonatorMode, -1f))
    }

    @Test
    fun duoEngineStaysAnInteger() {
        assertEquals(7, integerControlValue(VoiceSymbol.DUO_ENGINE_0.controlId, 7f))
    }

    @Test
    fun floatControlsAreNotIntegers() {
        assertNull(integerControlValue(ResonatorSymbol.MIX.controlId, 0.5f))
    }
}
