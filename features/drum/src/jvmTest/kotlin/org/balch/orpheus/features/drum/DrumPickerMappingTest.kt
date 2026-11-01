package org.balch.orpheus.features.drum

import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrumPickerMappingTest {

    @Test
    fun patchPickStoresItsEngineOrdinal() {
        val dx2 = EngineCatalog.patches.first { it.engine == OrpheusEngineId.DX2 && it.index == 24 }
        assertEquals(OrpheusEngineId.DX2.ordinal, drumOrdinalFor(EngineChoice.Patch(dx2)))
        assertEquals(23, drumOrdinalFor(EngineChoice.Patch(dx2)))
    }

    @Test
    fun everyDrumChoiceFitsTheDrumEngineRange() {
        EngineCatalog.choices(EngineSlotKind.DRUM, hasNative = true).forEach {
            assertTrue(drumOrdinalFor(it) <= 24, "${EngineCatalog.labelFor(it)} -> ${drumOrdinalFor(it)}")
        }
    }

    @Test
    fun hiddenFmDrumStillHasALabelledCurrentChoice() {
        val current = drumCurrentChoice(OrpheusEngineId.FM_DRUM.ordinal, 0.3f)
        assertEquals("FM", EngineCatalog.labelFor(current))
    }

    @Test
    fun outOfRangeOrdinalFallsBackToTheDefaultDrum() {
        assertEquals(EngineChoice.Engine(OrpheusEngineId.BD), drumCurrentChoice(99, 0f))
    }

    @Test
    fun dxOrdinalWithModReadsAsThatPatch() {
        val patch = EngineCatalog.patches.first { it.engine == OrpheusEngineId.DX2 && it.index == 24 }
        val current = drumCurrentChoice(OrpheusEngineId.DX2.ordinal, patch.harmonics)
        assertEquals(EngineChoice.Patch(patch), current)
    }
}
