package org.balch.orpheus.djapp.ai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.features.ai.AgentErrorLog
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.ai.tools.VibeApplyArgs
import org.balch.orpheus.features.ai.tools.VibeApplyTool
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.vibeTreeJson
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.Vibe

@OptIn(ExperimentalCoroutinesApi::class)
class VibeEditToolTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val night = vibe("Night Drive")
    private val pulsar = FakePulsar(listOf(night, vibe("Low Tide")))

    private class Archive : AiVibeArchive {
        val names = mutableListOf<String>()
        override suspend fun archive(vibeName: String, vibeJson: String): Boolean {
            names += vibeName
            return true
        }
    }

    private inner class Rig(val collab: CollabRig, val budget: TurnBudget, val bus: VibeCreateEventBus, val archive: Archive) {
        val edit = VibeEditTool(budget, collab.mixer).tool
        val apply = VibeApplyTool({ pulsar }, bus, archive, budget, setOf(collab.guard), AgentErrorLog()).tool
        val history get() = collab.mixer.history.value
    }

    private suspend fun TestScope.rig(): Rig {
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        val collab = CollabRig(pulsar, { controller }, backgroundScope)
        runCurrent()
        return Rig(collab, TurnBudget(), VibeCreateEventBus(), Archive())
    }

    private fun edits(vararg e: Pair<String, Any>) = VibeEditArgs(
        e.map { (path, v) ->
            VibeEdit(path, (if (v is String) JsonPrimitive(v) else JsonPrimitive(v as Number)).toString())
        },
    )

    private fun json(v: Vibe) = vibeApplyJson.encodeToString(v)

    @Test
    fun editToolRecordsRows() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val res = r.edit.execute(edits("bpm" to 96, "scaleType" to "MAJOR"))
        runCurrent()
        assertTrue(res.ok, res.message)
        assertEquals(2, res.rows)
        assertTrue("bpm" in res.message && "scaleType" in res.message && "restarted" in res.message, res.message)
        assertEquals(loads + 1, pulsar.vibeLoadCount.value)
        assertEquals(96f, pulsar.vibeFlow.value.bpm)
        assertEquals(setOf("bpm", "scaleType"), r.history.vibeEdits.map { it.path }.toSet())
    }

    @Test
    fun editToolRefusesMixerPath() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val res = r.edit.execute(edits("energy" to 0.9, "bpm" to 96))
        assertFalse(res.ok)
        assertTrue("mix_set" in res.message && "energy" in res.message, res.message)
        assertEquals(loads, pulsar.vibeLoadCount.value)
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun editToolBadValueFailsWithoutRows() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val res = r.edit.execute(edits("scaleType" to "NOT_A_SCALE"))
        assertFalse(res.ok)
        assertEquals(0, res.rows)
        assertTrue(res.message.isNotBlank())
        assertEquals(loads, pulsar.vibeLoadCount.value)
        assertTrue(r.history.items.isEmpty())
        // A path that is not in the vibe fails the same way.
        assertFalse(r.edit.execute(edits("tracks[40].pan" to 1)).ok)
        // A value that is not JSON text fails too.
        assertFalse(r.edit.execute(VibeEditArgs(listOf(VibeEdit("bpm", "{nope")))).ok)
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun editToolStructureRefusalSuggestsNewVibe() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val two = JsonArray(listOf("intro", "verse").map { vibeTreeJson.encodeToJsonElement(Section.serializer(), Section(it)) })
        val res = r.edit.execute(VibeEditArgs(listOf(VibeEdit("arrangement.sections", two.toString()))))
        assertFalse(res.ok)
        assertTrue("section count" in res.message, res.message)
        assertTrue("brand new vibe" in res.message && "pulsar_cast_vibe" in res.message && "pulsar_apply_vibe" in res.message, res.message)
        assertEquals(1, "brand new vibe".toRegex().findAll(res.message).count(), res.message)
        assertEquals(loads, pulsar.vibeLoadCount.value)
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun editToolSpendsBudget() = runTest {
        val r = rig()
        repeat(TurnBudget.MAX_APPLIES) { assertTrue(r.edit.execute(edits("bpm" to 90 + it)).ok) }
        val loads = pulsar.vibeLoadCount.value
        val spent = r.edit.execute(edits("bpm" to 130))
        assertFalse(spent.ok)
        assertEquals(TurnBudget.SPENT, spent.message)
        assertEquals(loads, pulsar.vibeLoadCount.value)
    }

    @Test
    fun applySameNameBecomesEditWithoutArchive() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val res = r.apply.execute(VibeApplyArgs(json(night.copy(bpm = 96f))))
        runCurrent()
        assertTrue(res.success, res.message)
        assertEquals("Night Drive", res.appliedName)
        assertTrue("Recorded as 1 History rows on 'Night Drive'" in res.message, res.message)
        assertTrue(r.archive.names.isEmpty())
        assertEquals(loads + 1, pulsar.vibeLoadCount.value)
        assertEquals(96f, pulsar.vibeFlow.value.bpm)
        assertEquals("bpm", r.history.vibeEdits.single().path)
        // No new-vibe event: the bus never saw a Generating or Generated.
        assertTrue(r.bus.events.replayCache.isEmpty(), "events: ${r.bus.events.replayCache}")
    }

    @Test
    fun applySameNameWithMixerChangesNamesThem() = runTest {
        val r = rig()
        val res = r.apply.execute(VibeApplyArgs(json(night.copy(bpm = 96f, energy = 0.9f))))
        runCurrent()
        assertTrue(res.success, res.message)
        assertTrue("energy" in res.message && "mix_set" in res.message, res.message)
        assertEquals(listOf("bpm"), r.history.vibeEdits.map { it.path })
        assertEquals(night.energy, pulsar.vibeFlow.value.energy)
    }

    @Test
    fun applyNewNameStillArchivesAndApplies() = runTest {
        val r = rig()
        val res = r.apply.execute(VibeApplyArgs(json(vibe("Velvet Static"))))
        runCurrent()
        assertTrue(res.success, res.message)
        assertEquals(listOf("Velvet Static"), r.archive.names)
        assertEquals("Velvet Static", pulsar.vibeFlow.value.name)
        assertNotNull(r.bus.events.replayCache.firstOrNull())
        assertTrue(r.history.vibeEdits.isEmpty())
        assertNull(res.refusal)
    }

    @Test
    fun editToolDescribesTheLoopAndMixSet() {
        val d = VibeEditTool(TurnBudget(), CollabRig(pulsar, { error("unused") }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })).mixer)
            .tool.descriptor
        assertEquals("pulsar_edit_vibe", d.name)
        assertTrue("mix_set" in d.description && "tracks[" in d.description)
    }

    @Test
    fun editToolDescriptionShowsAStringValueAndTheSpentApply() {
        val d = VibeEditTool(TurnBudget(), CollabRig(pulsar, { error("unused") }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })).mixer)
            .tool.descriptor
        assertTrue("""path "scaleType", value "\"MAJOR\""""" in d.description, d.description)
        assertTrue("refused or no-op" in d.description && "spends" in d.description, d.description)
    }

    @Test
    fun applySameNameStructureChangeSuggestsNewVibe() = runTest {
        val r = rig()
        val loads = pulsar.vibeLoadCount.value
        val shorter = night.copy(arrangement = night.arrangement!!.copy(sections = night.arrangement!!.sections.take(2)))
        val res = r.apply.execute(VibeApplyArgs(json(shorter)))
        runCurrent()
        assertFalse(res.success)
        assertTrue("section count" in res.message, res.message)
        assertTrue("brand new vibe" in res.message && "pulsar_cast_vibe" in res.message && "new original name" in res.message, res.message)
        assertEquals(loads, pulsar.vibeLoadCount.value)
    }
}
