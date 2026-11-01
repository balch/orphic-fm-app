package org.balch.orpheus.core.audio.patch

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.LegacyModSource.FLUX
import org.balch.orpheus.core.audio.patch.LegacyModSource.LFO
import org.balch.orpheus.core.audio.patch.LegacyModSource.OFF
import org.balch.orpheus.core.audio.patch.LegacyModSource.VOICE_FM
import org.balch.orpheus.core.audio.patch.PatchDest.HARMONICS
import org.balch.orpheus.core.audio.patch.PatchDest.PITCH
import org.balch.orpheus.core.audio.patch.PatchDest.TIMBRE
import org.balch.orpheus.core.audio.patch.PatchSource.FLUX_X2
import org.balch.orpheus.core.audio.patch.PatchSource.LFO_A
import org.balch.orpheus.core.audio.patch.PatchSource.LFO_A90
import org.balch.orpheus.core.audio.patch.PatchSource.LFO_AB
import org.balch.orpheus.core.audio.patch.PatchSource.LFO_B
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuoPatchTest {
    private val va = OrpheusEngineId.VA
    private val pitch12 = 0.5f / 12f

    @Test
    fun legacyLfoMakesFiveCables() {
        val expected = setOf(
            PatchCable(LFO_AB, TIMBRE, amount = 1f),
            PatchCable(LFO_A, PatchDest.MORPH, amount = 0.5f),
            PatchCable(LFO_B, HARMONICS, amount = 0.5f),
            PatchCable(LFO_A90, PITCH, amount = pitch12),
            PatchCable(LFO_AB, PatchDest.FM, amount = 0.5f),
        )
        val got = legacyDuoPatch(LFO, onDx = false, 0).cables
        assertEquals(expected, got.toSet())
        assertEquals(5, got.size)
        assertTrue(got.none { it.parked })
    }

    @Test
    fun legacyLfoOnDxParksHarmonics() {
        val parked = legacyDuoPatch(LFO, onDx = true, 0).cables.filter { it.parked }
        assertEquals(listOf(LFO_B), parked.map { it.source })
        assertEquals(listOf(HARMONICS), parked.map { it.dest })
    }

    @Test
    fun legacyFluxOddDuoUsesX2() {
        val cables = legacyDuoPatch(FLUX, false, 3).cables
        assertTrue(cables.isNotEmpty() && cables.all { it.source == FLUX_X2 })
    }

    @Test
    fun legacyOffIsEmpty() {
        assertEquals(emptyList(), legacyDuoPatch(OFF, false, 0).cables)
    }

    @Test
    fun pitchAmountTimesFullScaleIsExactlyHalf() {
        assertEquals(0.5f, 0.5f / 12f * PITCH.fullScale)
    }

    @Test
    fun connectUpsertsAndClamps() {
        val p = DuoPatch().connect(LFO_A, TIMBRE, amount = 0.2f).connect(LFO_A, TIMBRE, amount = 1.7f)
        assertEquals(1, p.cables.size)
        assertEquals(1f, p.cables[0].amount)
    }

    @Test
    fun splitDuplicatesBothCable() {
        val p = DuoPatch().connect(LFO_A, PITCH, amount = 0.4f).setBridged(PITCH, false)
        assertFalse(p.pitchBridged)
        assertEquals(
            setOf(VoiceSlot.A to 0.4f, VoiceSlot.B to 0.4f),
            p.cables.map { it.slot to it.amount }.toSet(),
        )
    }

    @Test
    fun bridgeMergesAWins() {
        val p = DuoPatch(pitchBridged = false)
            .connect(LFO_A, PITCH, VoiceSlot.A, 0.3f)
            .connect(LFO_A, PITCH, VoiceSlot.B, 0.7f)
            .connect(LFO_B, PITCH, VoiceSlot.B, 0.6f)
            .setBridged(PITCH, true)
        assertTrue(p.pitchBridged)
        assertEquals(
            setOf(Triple(LFO_A, VoiceSlot.BOTH, 0.3f), Triple(LFO_B, VoiceSlot.BOTH, 0.6f)),
            p.cables.map { Triple(it.source, it.slot, it.amount) }.toSet(),
        )
        assertEquals(2, p.cables.size)
    }

    private fun harmonicsPatch() = DuoPatch().connect(LFO_B, HARMONICS, amount = 0.5f)

    @Test
    fun arrivingOnDxParksHarmonics() {
        val p = harmonicsPatch().onEngineChange(va, OrpheusEngineId.DX)
        assertTrue(p.cables.single().parked)
    }

    @Test
    fun leavingDxWakesHarmonics() {
        val p = harmonicsPatch().onEngineChange(va, OrpheusEngineId.DX).onEngineChange(OrpheusEngineId.DX, va)
        assertFalse(p.cables.single().parked)
    }

    @Test
    fun dxToDxKeepsWokenCable() {
        val p = harmonicsPatch().onEngineChange(OrpheusEngineId.DX, OrpheusEngineId.DX2)
        assertFalse(p.cables.single().parked)
    }

    @Test
    fun cellAmountsExpandBothAndZeroParked() {
        val p = DuoPatch()
            .connect(LFO_A, PITCH, amount = 0.25f)
            .connect(LFO_B, HARMONICS, amount = 0.5f)
            .onEngineChange(va, OrpheusEngineId.DX)
        val cells = p.cellAmounts()
        assertEquals(PATCH_CELLS, cells.size)
        assertEquals(0.25f, cells[patchCellIndex(0, LFO_A, PITCH)])
        assertEquals(0.25f, cells[patchCellIndex(1, LFO_A, PITCH)])
        assertEquals(0f, cells[patchCellIndex(0, LFO_B, HARMONICS)])
        assertEquals("p1_1_2_0", patchCellSymbol(1, patchCellIndex(1, LFO_B, HARMONICS)))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val p = legacyDuoPatch(LFO, true, 0).setBridged(PITCH, false)
        assertEquals(p, DuoPatch.decode(p.encode()))
    }

    @Test
    fun decodeDropsUnknownCables() {
        val json = """{"cables":[{"source":"NOPE","dest":"TIMBRE","amount":1.0},
            {"source":"LFO_A","dest":"MORPH","amount":0.5}]}"""
        assertEquals(listOf(PatchCable(LFO_A, PatchDest.MORPH, amount = 0.5f)), DuoPatch.decode(json)?.cables)
        assertNull(DuoPatch.decode("garbage"))
    }

    @Test
    fun patchFromPresetToleratesFloatAndMissing() {
        assertEquals(legacyDuoPatch(LFO, false, 0), patchFromPreset(null, 2f, va, 0))
        assertEquals(legacyDuoPatch(VOICE_FM, false, 0), patchFromPreset(null, null, va, 0))
        assertEquals(legacyDuoPatch(LFO, false, 0), patchFromPreset("garbage", 2f, va, 0))
        assertEquals(DuoPatch(), patchFromPreset("garbage", null, va, 0))
    }

    @Test
    fun modulatedDestsSkipsParkedAndUnreachable() {
        val p = DuoPatch().connect(LFO_A, TIMBRE, amount = 1f).connect(LFO_B, HARMONICS, amount = 1f)
        assertFalse(TIMBRE in p.modulatedDests(OrpheusEngineId.OSC))
        assertTrue(TIMBRE in p.modulatedDests(va))
        val dx = p.onEngineChange(va, OrpheusEngineId.DX)
        assertFalse(HARMONICS in dx.modulatedDests(OrpheusEngineId.DX))
        assertTrue(HARMONICS in p.modulatedDests(va))
    }

    @Test
    fun connectBothOnSplitRowMakesAAndB() {
        val p = DuoPatch(pitchBridged = false).connect(LFO_A, PITCH, VoiceSlot.BOTH, 0.4f)
        assertEquals(setOf(VoiceSlot.A, VoiceSlot.B), p.cables.map { it.slot }.toSet())
        assertEquals(2, p.cables.size)
    }

    @Test
    fun connectAOnBridgedRowStoresBoth() {
        val p = DuoPatch().connect(LFO_A, PITCH, VoiceSlot.A, 0.4f)
        assertEquals(listOf(VoiceSlot.BOTH), p.cables.map { it.slot })
    }

    @Test
    fun disconnectRemovesOnlyThatCable() {
        val p = DuoPatch().connect(LFO_A, TIMBRE, amount = 1f).connect(LFO_B, TIMBRE, amount = 1f)
            .disconnect(LFO_A, TIMBRE, VoiceSlot.BOTH)
        assertEquals(listOf(LFO_B), p.cables.map { it.source })
    }

    @Test
    fun setAmountAndWakeEditOneCable() {
        val base = DuoPatch().connect(LFO_A, HARMONICS, amount = 0.1f).connect(LFO_B, HARMONICS, amount = 0.2f)
            .onEngineChange(va, OrpheusEngineId.DX)
        val p = base.setAmount(LFO_A, HARMONICS, VoiceSlot.BOTH, 0.9f).wake(LFO_A, HARMONICS, VoiceSlot.BOTH)
        val a = p.cables.single { it.source == LFO_A }
        val b = p.cables.single { it.source == LFO_B }
        assertEquals(0.9f, a.amount)
        assertFalse(a.parked)
        assertEquals(0.2f, b.amount)
        assertTrue(b.parked)
    }

    @Test
    fun reachesEngineBraidsAndChaos() {
        assertFalse(TIMBRE.reachesEngine(OrpheusEngineId.CSAW))
        assertTrue(PITCH.reachesEngine(OrpheusEngineId.CSAW))
        assertFalse(TIMBRE.reachesEngine(OrpheusEngineId.LRZ))
        assertFalse(PITCH.reachesEngine(OrpheusEngineId.LRZ))
        assertTrue(HARMONICS.reachesEngine(OrpheusEngineId.LRZ))
        assertTrue(PatchDest.FM.reachesEngine(OrpheusEngineId.OSC))
        assertFalse(PatchDest.FM.reachesEngine(va))
    }

    @Test
    fun decodeNormalisesDuplicatesAndRowSlots() {
        val json = """{"cables":[
            {"source":"LFO_A","dest":"TIMBRE","amount":0.1},
            {"source":"LFO_A","dest":"TIMBRE","amount":0.9},
            {"source":"LFO_A","dest":"PITCH","slot":"B","amount":0.7},
            {"source":"LFO_A","dest":"PITCH","slot":"A","amount":0.3},
            {"source":"LFO_B","dest":"ENV","slot":"BOTH","amount":0.5}],
            "envBridged":false}"""
        val p = DuoPatch.decode(json)!!
        assertEquals(0.1f, p.cables.single { it.dest == TIMBRE }.amount)
        val pitch = p.cables.single { it.dest == PITCH }
        assertEquals(VoiceSlot.BOTH to 0.3f, pitch.slot to pitch.amount)
        assertEquals(setOf(VoiceSlot.A, VoiceSlot.B), p.cables.filter { it.dest == PatchDest.ENV }.map { it.slot }.toSet())
    }

    @Test
    fun decodeNanAmountIsZero() {
        val p = DuoPatch.decode("""{"cables":[{"source":"LFO_A","dest":"TIMBRE","amount":NaN}]}""")!!
        assertEquals(0f, p.cables.single().amount)
        assertEquals(0f, DuoPatch().connect(LFO_A, TIMBRE, amount = Float.NaN).cables.single().amount)
    }

    @Test
    fun voiceDestsAreSlotAware() {
        val p = DuoPatch(
            cables = listOf(
                PatchCable(LFO_A, PatchDest.ENV, VoiceSlot.A, 0.5f),
                PatchCable(LFO_A, PITCH, VoiceSlot.BOTH, 0.5f),
                PatchCable(LFO_B, PatchDest.MORPH, VoiceSlot.BOTH, 0.5f),
                PatchCable(LFO_B, PatchDest.ENV, VoiceSlot.B, 0.5f, parked = true),
            ),
        )
        assertEquals(setOf(PatchDest.ENV, PITCH), p.modulatedVoiceDests(va, VoiceSlot.A))
        assertEquals(setOf(PITCH), p.modulatedVoiceDests(va, VoiceSlot.B))
    }
}
