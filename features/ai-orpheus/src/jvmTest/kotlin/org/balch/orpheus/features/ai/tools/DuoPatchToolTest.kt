package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuoPatchToolTest {

    private class FakeBay(var engine: OrpheusEngineId? = null) : DuoPatchBay {
        private val flow = MutableStateFlow(List(6) { DuoPatch() })
        override val patches: StateFlow<List<DuoPatch>> = flow
        override fun setPatch(duo: Int, patch: DuoPatch) {
            flow.value = flow.value.mapIndexed { i, p -> if (i == duo) patch else p }
        }
        override fun update(duo: Int, transform: (DuoPatch) -> DuoPatch) = setPatch(duo, transform(flow.value[duo]))
        override fun applyLegacySource(duo: Int, source: LegacyModSource) = Unit
        override fun engineOf(duo: Int): OrpheusEngineId? = engine
    }

    private fun run(bay: DuoPatchBay, args: DuoPatchArgs) = executeDuoPatch(bay, args)

    @Test
    fun connectAddsCable() {
        val bay = FakeBay()
        val r = run(bay, DuoPatchArgs("connect", 1, "LFO_A", "TIMBRE", amount = 0.6f))
        assertTrue(r.success, r.message)
        val cable = bay.patches.value[1].cables.single()
        assertEquals(PatchSource.LFO_A, cable.source)
        assertEquals(PatchDest.TIMBRE, cable.dest)
        assertEquals(0.6f, cable.amount)
        assertContains(r.patch, "LFO_A")
    }

    @Test
    fun disconnectRemovesCable() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.5f))
        val r = run(bay, DuoPatchArgs("disconnect", 0, "LFO_A", "MORPH"))
        assertTrue(r.success, r.message)
        assertTrue(bay.patches.value[0].cables.isEmpty())
    }

    @Test
    fun disconnectBothRemovesSplitPitchCables() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.2f).setBridged(PatchDest.PITCH, false))
        val r = run(bay, DuoPatchArgs("disconnect", 0, "LFO_A90", "PITCH"))
        assertTrue(r.success, r.message)
        assertTrue(bay.patches.value[0].cables.isEmpty())
    }

    @Test
    fun wakeOnUnparkedCableFails() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.5f))
        assertFalse(run(bay, DuoPatchArgs("wake", 0, "LFO_A", "MORPH")).success)
    }

    @Test
    fun setAmountChangesAmount() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.WAVES_1, PatchDest.MORPH, amount = 0.5f))
        val r = run(bay, DuoPatchArgs("set_amount", 0, "WAVES_1", "MORPH", amount = -0.25f))
        assertTrue(r.success, r.message)
        assertEquals(-0.25f, bay.patches.value[0].cables.single().amount)
    }

    @Test
    fun wakeUnparks() {
        val bay = FakeBay()
        val parked = DuoPatch().connect(PatchSource.LFO_B, PatchDest.HARMONICS, amount = 0.5f)
            .onEngineChange(null, OrpheusEngineId.entries.first { it.forcePinHarmonics })
        bay.setPatch(0, parked)
        assertTrue(bay.patches.value[0].cables.single().parked)
        val r = run(bay, DuoPatchArgs("wake", 0, "LFO_B", "HARMONICS"))
        assertTrue(r.success, r.message)
        assertFalse(bay.patches.value[0].cables.single().parked)
    }

    @Test
    fun bridgeFalseSplitsPitch() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.LFO_A90, PatchDest.PITCH, amount = 0.2f))
        val r = run(bay, DuoPatchArgs("bridge", 0, dest = "PITCH", bridged = false))
        assertTrue(r.success, r.message)
        val p = bay.patches.value[0]
        assertFalse(p.pitchBridged)
        assertEquals(setOf(VoiceSlot.A, VoiceSlot.B), p.cables.map { it.slot }.toSet())
    }

    @Test
    fun readListsParkedCables() {
        val bay = FakeBay()
        val dx = OrpheusEngineId.entries.first { it.forcePinHarmonics }
        bay.setPatch(2, DuoPatch().connect(PatchSource.LFO_B, PatchDest.HARMONICS, amount = 0.5f).onEngineChange(null, dx))
        val r = run(bay, DuoPatchArgs("read", 2))
        assertTrue(r.success)
        assertContains(r.patch, "LFO_B")
        assertContains(r.patch, "parked")
    }

    @Test
    fun connectHarmonicsOnDxEngineIsLive() {
        val bay = FakeBay(OrpheusEngineId.entries.first { it.forcePinHarmonics })
        run(bay, DuoPatchArgs("connect", 0, "LFO_A", "HARMONICS", amount = 0.5f))
        assertFalse(bay.patches.value[0].cables.single().parked)
    }

    @Test
    fun unknownActionReportedBeforeOtherChecks() {
        val r = run(FakeBay(), DuoPatchArgs("frobnicate", 0, source = "NOPE", dest = "NOPE", slot = "NOPE"))
        assertFalse(r.success)
        assertContains(r.message, "Unknown action")
    }

    @Test
    fun badSourceFailsWithoutChange() {
        val bay = FakeBay()
        bay.setPatch(0, DuoPatch().connect(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.5f))
        val before = bay.patches.value[0]
        val r = run(bay, DuoPatchArgs("connect", 0, "NOPE", "TIMBRE", amount = 0.5f))
        assertFalse(r.success)
        assertEquals(before, bay.patches.value[0])
    }

    @Test
    fun badDuoFailsWithoutChange() {
        val bay = FakeBay()
        val r = run(bay, DuoPatchArgs("connect", 9, "LFO_A", "TIMBRE", amount = 0.5f))
        assertFalse(r.success)
    }
}
