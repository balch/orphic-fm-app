package org.balch.orpheus.core.audio

import org.balch.orpheus.core.audio.EngineParam.HARMONICS
import org.balch.orpheus.core.audio.EngineParam.MORPH
import org.balch.orpheus.core.audio.EngineParam.TIMBRE
import org.balch.orpheus.core.audio.SignalPath.PANEL
import org.balch.orpheus.core.audio.SignalPath.PULSAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineKnobMeaningsTest {

    private fun short(id: OrpheusEngineId, p: EngineParam, path: SignalPath = PULSAR) = knobMeaning(id, p, path)?.short

    @Test
    fun everyIdHasPulsarRow() {
        for (id in OrpheusEngineId.entries) for (p in EngineParam.entries) {
            val m = knobMeaning(id, p, PULSAR)
            assertNotNull(m, "$id $p")
            assertTrue(m.short != p.name.lowercase().take(5), "$id $p falls back")
        }
    }

    @Test
    fun auditFixes() {
        for (path in SignalPath.entries) {
            assertEquals("fdbk", short(OrpheusEngineId.OSC, HARMONICS, path))
            assertEquals("tri/sq", short(OrpheusEngineId.OSC, TIMBRE, path))
            assertEquals("detune", short(OrpheusEngineId.OSC, MORPH, path))
        }
        assertEquals("chord", knobMeaning(OrpheusEngineId.ENS, HARMONICS, PULSAR)?.long)
        assertEquals("reg", short(OrpheusEngineId.ENS, MORPH))
        assertEquals("intvl2", short(OrpheusEngineId.TRIPLE_SAW, MORPH))
    }

    @Test
    fun panelOverrides() {
        assertEquals("word", short(OrpheusEngineId.SPK, MORPH, PULSAR))
        assertFalse(knobMeaning(OrpheusEngineId.SPK, MORPH, PANEL)!!.active)
        assertNull(knobMeaning(OrpheusEngineId.MST, HARMONICS, PANEL))
        assertNotNull(knobMeaning(OrpheusEngineId.MST, HARMONICS, PULSAR))
    }

    @Test
    fun dxHarmonicsIsPatchAndLocked() {
        assertEquals("patch", short(OrpheusEngineId.DX2, HARMONICS))
        assertTrue(OrpheusEngineId.DX2.forcePinHarmonics)
    }

    @Test
    fun shortLabelsFit() {
        for (id in OrpheusEngineId.entries) for (p in EngineParam.entries) for (path in SignalPath.entries) {
            val m = knobMeaning(id, p, path) ?: continue
            assertTrue(m.short.length <= 8, "$id $p $path ${m.short}")
        }
    }
}
