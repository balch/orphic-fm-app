package org.balch.orpheus.core.audio

import org.balch.orpheus.core.audio.OrpheusEngineId.BD
import org.balch.orpheus.core.audio.OrpheusEngineId.DX
import org.balch.orpheus.core.audio.OrpheusEngineId.DX2
import org.balch.orpheus.core.audio.OrpheusEngineId.DX3
import org.balch.orpheus.core.audio.OrpheusEngineId.FM_DRUM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineCatalogTest {

    private fun engines(choices: List<EngineChoice>) = choices.map { it.engine }.toSet()
    private fun patchNames(choices: List<EngineChoice>) =
        choices.filterIsInstance<EngineChoice.Patch>().map { it.patch.name }

    @Test
    fun everyIdHasCategoryExceptFmDrum() {
        for (id in OrpheusEngineId.entries) {
            assertEquals(id != FM_DRUM, EngineCatalog.category(id) != null, "category of $id")
        }
        for (id in listOf(DX, DX2, DX3)) {
            assertEquals(EngineCategory.DX_PATCHES, EngineCatalog.category(id))
        }
    }

    @Test
    fun patchesRoundTrip() {
        assertEquals(96, EngineCatalog.patches.size)
        for (p in EngineCatalog.patches) {
            assertEquals(p, EngineCatalog.patchAt(p.engine, p.harmonics))
            assertEquals(p.name, FmPatchNames.patchNameFor(p.engine, p.harmonics))
        }
    }

    @Test
    fun familiesMatchSpec() {
        fun family(e: OrpheusEngineId, i: Int) =
            EngineCatalog.patches.single { it.engine == e && it.index == i }.family
        assertEquals("Bass", family(DX, 19))
        assertEquals("Mallets & Bells", family(DX2, 16))
        assertEquals("Brass", family(DX3, 31))
    }

    @Test
    fun duoChoicesExcludePulsarOnlyAndDrums() {
        val choices = EngineCatalog.choices(EngineSlotKind.DUO, hasNative = true)
        val ids = engines(choices)
        for (id in listOf(OrpheusEngineId.MST, BD, OrpheusEngineId.SD, OrpheusEngineId.HH, FM_DRUM)) {
            assertFalse(id in ids, "$id")
        }
        assertTrue(choices.any { it is EngineChoice.Patch && it.patch.engine == DX2 && it.patch.index == 0 })
    }

    @Test
    fun drumChoicesStopAtDx3() {
        val choices = EngineCatalog.choices(EngineSlotKind.DRUM, hasNative = true)
        val ids = engines(choices)
        assertTrue(BD in ids)
        assertTrue(choices.any { it is EngineChoice.Patch && it.patch.engine == DX2 && it.patch.index == 24 && it.patch.name == "Kettle" })
        for (id in listOf(OrpheusEngineId.OSC, OrpheusEngineId.TRIPLE_SAW, OrpheusEngineId.LRZ, FM_DRUM)) {
            assertFalse(id in ids, "$id")
        }
    }

    @Test
    fun noNativeHidesNativeOnly() {
        val choices = EngineCatalog.choices(EngineSlotKind.DUO, hasNative = false)
        assertFalse(OrpheusEngineId.VCF in engines(choices))
        assertTrue(choices.none { it is EngineChoice.Patch })
    }

    @Test
    fun searchFindsPatchAcrossCategories() {
        val all = EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true)
        val hits = patchNames(EngineCatalog.search(all, "piano"))
        assertTrue("E piano 1" in hits)
        assertTrue("Grd Piano" in hits)
        assertEquals(hits, patchNames(EngineCatalog.search(all, "PIANO")))
        assertTrue(EngineCatalog.search(all, "zzz").isEmpty())
    }

    @Test
    fun labelsAndKeys() {
        val p = EngineCatalog.patches.single { it.engine == DX2 && it.index == 0 }
        assertEquals("DX2 · E piano 1", EngineCatalog.labelFor(EngineChoice.Patch(p)))
        assertEquals("FM", EngineCatalog.labelFor(EngineChoice.Engine(FM_DRUM)))
        assertEquals("DX2:0", EngineCatalog.key(EngineChoice.Patch(p)))
        for (c in EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true)) {
            assertTrue(EngineCatalog.labelFor(c).isNotEmpty())
            assertEquals(c, EngineCatalog.fromKey(EngineCatalog.key(c)))
        }
        assertNull(EngineCatalog.fromKey("nope"))
        assertNotNull(EngineCatalog.fromKey("VA"))
    }

    @Test
    fun choiceForPicksPatchOnDx() {
        val c = EngineCatalog.choiceFor(DX2, 0.5f)
        assertTrue(c is EngineChoice.Patch)
        assertEquals(EngineChoice.Engine(OrpheusEngineId.VA), EngineCatalog.choiceFor(OrpheusEngineId.VA, 0.5f))
    }

    @Test
    fun recentDedupesAndCaps() {
        var recent = emptyList<String>()
        val ids = OrpheusEngineId.entries.take(10)
        for (id in ids) recent = pushRecent(recent, EngineChoice.Engine(id))
        recent = pushRecent(recent, EngineChoice.Engine(ids[3]))
        assertEquals(8, recent.size)
        assertEquals(EngineCatalog.key(EngineChoice.Engine(ids[3])), recent.first())
        assertEquals(recent.size, recent.toSet().size)
    }
}
