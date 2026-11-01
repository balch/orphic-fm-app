package org.balch.orpheus.features.pulsar.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.FmPatchNames
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.WhalesDreamVibe

class EngineKnobsTest {
    private val whale: Vibe = WhalesDreamVibe().vibe

    @Test fun knobPathsAreTheDiffPaths() {
        for (slot in EngineSlot.entries) for (k in EngineKnob.entries) {
            val e = whale.tracks[3].engine(slot)
            val edited = whale.withEngineKnob(3, slot, k, if (e.knob(k) > 0.5f) 0.1f else 0.9f)
            val paths = diffVibe(whale, edited).map { it.path }.toSet()
            assertTrue(knobPath(3, slot, k) in paths, "$slot $k: $paths")
            assertTrue(paths.all { it == knobPath(3, slot, k) || it == knobPinPath(3, slot, k) }, "$slot $k: $paths")
        }
    }

    @Test fun aTurnPinsAndAResetRestoresTheFile() {
        val turned = whale.withEngineKnob(1, EngineSlot.EDM, EngineKnob.TIMBRE, 0.77f)
        assertEquals(0.77f, turned.tracks[1].engineEdm.timbre)
        assertTrue(turned.tracks[1].engineEdm.pinTimbre)
        assertEquals(whale, turned.withEngineKnobOf(whale, 1, EngineSlot.EDM, EngineKnob.TIMBRE))
    }

    @Test fun onlyKnobFieldsRetune() {
        val knob = whale.withEngineKnob(0, EngineSlot.SPACE, EngineKnob.MORPH, 0.2f)
        assertTrue(whale.retunesTo(knob))
        assertTrue(whale.retunesTo(whale))
        val e = whale.tracks[0].engineEdm
        val volume = whale.copy(tracks = whale.tracks.toMutableList().also { it[0] = it[0].copy(engineEdm = e.copy(volume = e.volume + 0.1f)) })
        assertFalse(whale.retunesTo(volume))
        val engine = whale.copy(tracks = whale.tracks.toMutableList().also { it[0] = it[0].copy(engineEdm = e.copy(engineId = OrpheusEngineId.STR)) })
        assertFalse(whale.retunesTo(engine), "an engine switch reloads")
        assertFalse(whale.retunesTo(knob.copy(bpm = whale.bpm + 1f)))
    }

    @Test fun dxHarmonicsSnapsToTheMiddleOfItsPatch() {
        for (patch in listOf(0, 7, 16, 31)) {
            val inside = (patch + 0.9f) / (32f * 1.02f)
            val snapped = EngineKnob.HARMONICS.snapped(OrpheusEngineId.DX2, inside)
            assertEquals((patch + 0.5f) / (32f * 1.02f), snapped, 1e-6f)
            assertEquals(FmPatchNames.patchNameAt(OrpheusEngineId.DX2, patch), knobValueText(OrpheusEngineId.DX2, EngineKnob.HARMONICS, snapped) { "$it" })
        }
        assertEquals(32, knobSteps(OrpheusEngineId.DX, EngineKnob.HARMONICS))
        assertNull(knobSteps(OrpheusEngineId.DX, EngineKnob.TIMBRE))
        assertEquals(0.5f, EngineKnob.HARMONICS.snapped(OrpheusEngineId.VA, 0.5f))
    }

    @Test fun slotHoldsBetweenTheThresholds() {
        assertEquals(EngineSlot.EDM, slotAt(0.61f, EngineSlot.SPACE))
        assertEquals(EngineSlot.SPACE, slotAt(0.39f, EngineSlot.EDM))
        assertEquals(EngineSlot.SPACE, slotAt(0.5f, EngineSlot.SPACE))
        assertEquals(EngineSlot.EDM, slotAt(0.5f, EngineSlot.EDM))
    }

    @Test fun unpinnedKnobsFollowTheirMacro() {
        val tv = whale.tracks[0].let {
            it.copy(macroMap = it.macroMap.copy(moodHarmonics = MacroTarget(0.2f, 0.6f), spaceDecay = MacroTarget(0f, 1f)))
        }
        assertEquals(0.4f, tv.macroValue(EngineKnob.HARMONICS, mood = 0.5f, space = 0f), 1e-6f)
        assertEquals(0.25f, tv.macroValue(EngineKnob.MORPH, mood = 1f, space = 0.25f), 1e-6f)
    }

    @Test fun braidsDropsTimbre() {
        assertFalse(knobMeaning(OrpheusEngineId.VOWEL_FOF, EngineKnob.TIMBRE).active)
        assertEquals("vowel", knobMeaning(OrpheusEngineId.VOWEL_FOF, EngineKnob.HARMONICS).short)
        assertTrue(OrpheusEngineId.entries.all { id -> EngineKnob.entries.all { knobMeaning(id, it).short.length <= 8 } })
    }
}
