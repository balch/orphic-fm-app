package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.tools.CollabRig
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MemoryChangeStore
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.ScratchEffect
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.Vibe

@OptIn(ExperimentalCoroutinesApi::class)
class CollabVibeEditTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val night = vibe("Night Drive")
    private val pulsar = FakePulsar(listOf(night, vibe("Low Tide")))

    private class Rig(val pulsar: FakePulsar, val controller: MixBenchController, val collab: CollabRig) {
        val mixer get() = collab.mixer
        val history get() = collab.mixer.history.value
    }

    private suspend fun TestScope.rig(
        store: MixChangeStore = MemoryChangeStore(),
        p: FakePulsar = pulsar,
        capture: MixCaptureService? = null,
    ): Rig {
        val controller = MixBenchController(p, synth, NoMeterTap, backgroundScope).also { it.start() }
        val collab = if (capture == null) {
            CollabRig(p, { controller }, backgroundScope, changeStore = store)
        } else {
            CollabRig(p, { controller }, backgroundScope, changeStore = store, captureService = { capture })
        }
        runCurrent()
        return Rig(p, controller, collab)
    }

    private suspend fun Rig.level(t: Int, db: Float, origin: EditOrigin = EditOrigin.USER) =
        mixer.edit(origin, MixControl.Level(t)) { setHeardLevelDb(t, db) }

    private suspend fun Rig.edit(v: Vibe, origin: EditOrigin = EditOrigin.USER) = mixer.editVibe(origin, v)

    private fun Rig.loads() = pulsar.vibeLoadCount.value

    private fun Rig.assertReplay() = assertEquals(history.layers(), controller.layers.value)

    private suspend fun TestScope.agentTurn(r: Rig, label: String, block: suspend () -> Unit) {
        r.collab.collab.beginTurn(label)
        runCurrent()
        block()
        r.collab.collab.endTurn()
        runCurrent()
    }

    private fun Rig.kinds() = history.items.map { item ->
        when (item) {
            is HistoryPoint -> "${item.kind} ${item.label}"
            is HistoryChange -> "change ${item.origin}"
            is HistoryVibeEdit -> "vibe ${item.origin} ${item.path}"
        }
    }

    @Test
    fun editVibeRecordsRowsAndAppliesEffective() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.collab.collab.togglePin(3)
        val loads = r.loads()

        val result = r.edit(night.copy(bpm = 96f, energy = 0.9f))
        runCurrent()

        assertEquals(VibeEditResult.Applied(1, listOf("energy")), result)
        assertEquals(loads + 1, r.loads())
        assertEquals(night.copy(bpm = 96f), pulsar.vibeFlow.value)
        val row = r.history.vibeEdits.single()
        assertEquals("bpm", row.path)
        assertEquals(EditOrigin.USER, row.origin)
        // The store keys the set by the file, never the effective vibe.
        val (saved, set) = assertNotNull(store.sets["Night Drive"])
        assertEquals(night, saved)
        assertEquals(r.history, set)
        assertEquals(night, r.mixer.fileVibe())
        assertEquals(setOf(3), r.collab.collab.pins.value, "an own load is not a new vibe")
    }

    @Test
    fun editVibeThatChangesNothingDoesNotReload() = runTest {
        val r = rig()
        r.edit(night.copy(bpm = 96f))
        val loads = r.loads()
        assertEquals(VibeEditResult.Applied(0, emptyList()), r.edit(night.copy(bpm = 96f)))
        assertEquals(loads, r.loads())
    }

    @Test
    fun editVibeKeepsMixRows() = runTest {
        val r = rig()
        r.level(2, -4f)
        r.edit(night.copy(bpm = 96f))
        runCurrent()
        assertEquals(MixControl.Level(2), r.history.changes.single().control)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[2])
        r.assertReplay()
    }

    @Test
    fun editVibeRefusesOtherName() = runTest {
        val r = rig()
        val loads = r.loads()
        val refused = assertIs<VibeEditResult.Refused>(r.edit(vibe("Low Tide").copy(bpm = 90f)))
        assertTrue("not the playing vibe" in refused.reason)
        assertEquals(loads, r.loads())
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun editVibeRefusesStructureChange() = runTest {
        val r = rig()
        val loads = r.loads()
        val shorter = night.copy(arrangement = Arrangement(sections = listOf(Section("intro"), Section("verse"))))
        val refused = assertIs<VibeEditResult.Refused>(r.edit(shorter))
        assertTrue("section count" in refused.reason)
        assertEquals(loads, r.loads())
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun agentEditVibeIsBracketedByCaptures() = runTest {
        val r = rig()
        // Only a vibe row is uncaptured, which still earns the turn a before-capture.
        r.edit(night.copy(scaleType = ScaleType.MAJOR))
        agentTurn(r, "Thin guitar") { r.edit(night.copy(scaleType = ScaleType.MAJOR, bpm = 96f), EditOrigin.AGENT) }
        assertEquals(
            listOf("vibe USER scaleType", "CAPTURE before: Thin guitar", "vibe AGENT bpm", "CAPTURE after: Thin guitar"),
            r.kinds(),
        )
        assertEquals(2, r.collab.capture.calls)
    }

    @Test
    fun twoEditsInOneTurnFold() = runTest {
        val r = rig()
        agentTurn(r, "Slower") {
            r.edit(night.copy(bpm = 96f), EditOrigin.AGENT)
            r.edit(night.copy(bpm = 100f), EditOrigin.AGENT)
        }
        val row = r.history.vibeEdits.single()
        assertEquals(JsonPrimitive(120f), row.before)
        assertEquals(JsonPrimitive(100f), row.after)
        assertEquals(100f, pulsar.vibeFlow.value.bpm)
    }

    @Test
    fun rollBackPastVibeRowReappliesFile() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.edit(night.copy(bpm = 96f))
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        val loads = r.loads()

        assertTrue(r.mixer.rollBack(point))
        runCurrent()

        assertEquals(night, pulsar.vibeFlow.value)
        assertEquals(loads + 1, r.loads())
        assertTrue(r.history.vibeEdits.isEmpty())
        assertEquals(-3f, r.controller.layers.value.vibe.levelDb[1])
        r.assertReplay()
    }

    @Test
    fun rollBackOverMixRowsOnlyDoesNotReload() = runTest {
        val r = rig()
        r.edit(night.copy(bpm = 96f))
        r.mixer.markPoint()
        r.level(1, -3f)
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        val loads = r.loads()
        assertTrue(r.mixer.rollBack(point))
        assertEquals(loads, r.loads())
        assertEquals(96f, pulsar.vibeFlow.value.bpm)
        r.assertReplay()
    }

    @Test
    fun hearPointWithFewerVibeRowsReappliesThenEndHearRestores() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.edit(night.copy(bpm = 96f))
        r.level(2, -5f)
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id

        assertTrue(r.mixer.hear(point))
        runCurrent()
        assertEquals(point, r.mixer.hearing.value)
        assertEquals(night, pulsar.vibeFlow.value)
        assertEquals(listOf(0f, -3f, 0f), r.controller.layers.value.vibe.levelDb.take(3))

        r.mixer.endHear()
        runCurrent()
        assertNull(r.mixer.hearing.value)
        assertEquals(night.copy(bpm = 96f), pulsar.vibeFlow.value)
        assertEquals(2, r.history.changes.size)
        r.assertReplay()
    }

    @Test
    fun editByPathsDuringAHearKeepsTheRowsAboveThePoint() = runTest {
        val r = rig()
        r.edit(night.copy(bpm = 96f))
        r.mixer.markPoint()
        r.edit(night.copy(bpm = 96f, scaleType = ScaleType.MAJOR))
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        assertTrue(r.mixer.hear(point))
        runCurrent()
        assertEquals(night.copy(bpm = 96f), pulsar.vibeFlow.value)

        val result = r.mixer.editVibe(EditOrigin.USER, listOf("bpm" to JsonPrimitive(110)))
        runCurrent()

        assertIs<VibeEditResult.Applied>(result)
        assertEquals(110f, pulsar.vibeFlow.value.bpm)
        assertEquals(ScaleType.MAJOR, pulsar.vibeFlow.value.scaleType)
        assertTrue(r.history.vibeEdits.any { it.path == "scaleType" && it.on })
    }

    @Test
    fun editByPathsThatDoNotFitIsInvalid() = runTest {
        val r = rig()
        val loads = r.loads()
        val result = r.mixer.editVibe(EditOrigin.USER, listOf("tracks.2.pan" to JsonPrimitive(0.5)))
        assertIs<VibeEditResult.Invalid>(result)
        assertEquals(loads, r.loads())
        assertTrue(r.history.vibeEdits.isEmpty())
    }

    @Test
    fun toggleThatBreaksDecodeIsRefused() = runTest {
        val r = rig()
        fun withExit(ms: Int) = night.copy(
            arrangement = night.arrangement!!.copy(
                sections = night.arrangement!!.sections.mapIndexed { i, s ->
                    if (i == 0) s.copy(exitEffects = listOf(ScratchEffect(ms))) else s
                },
            ),
        )
        r.edit(withExit(500))
        r.mixer.markPoint()
        r.edit(withExit(300))
        val whole = r.history.vibeEdits.first()
        assertEquals("arrangement.sections[0].exitEffects", whole.path)
        val before = r.history
        val loads = r.loads()
        val playing = pulsar.vibeFlow.value

        // The leaf row above the point indexes into the list the whole row made.
        assertFalse(r.mixer.toggleChange(whole.id))

        assertEquals(before, r.history)
        assertEquals(loads, r.loads())
        assertEquals(playing, pulsar.vibeFlow.value)
    }

    @Test
    fun toggleVibeRowOffPlaysTheFile() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.edit(night.copy(bpm = 96f))
        assertTrue(r.mixer.toggleChange(r.history.vibeEdits.single().id))
        runCurrent()
        assertEquals(night, pulsar.vibeFlow.value)
        assertFalse(r.history.vibeEdits.single().on)
        r.assertReplay()
    }

    @Test
    fun relaunchRestoresVibeRowsWithoutDroppingSet() = runTest {
        val store = MemoryChangeStore()
        val first = rig(store)
        first.level(1, -4f)
        first.edit(night.copy(bpm = 96f))
        val saved = first.history.items

        // A relaunch: the file vibe loads by name, and the new mixer restores its set at the first claim.
        val p = FakePulsar(listOf(night, vibe("Low Tide")))
        val r = rig(store, p)
        runCurrent()

        assertEquals(saved, r.history.items)
        assertEquals(night.copy(bpm = 96f), p.vibeFlow.value)
        assertEquals(1, p.vibeLoadCount.value)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
        r.assertReplay()
        assertEquals(night, store.sets["Night Drive"]?.first)
        assertEquals(saved, store.sets["Night Drive"]?.second?.items)
    }

    @Test
    fun changedFileDropsVibeRowSet() = runTest {
        val store = MemoryChangeStore()
        val first = rig(store)
        first.edit(night.copy(bpm = 96f))
        // The file was rebuilt since: the saved set is for the old content.
        store.sets["Night Drive"] = night.copy(scaleType = ScaleType.MAJOR) to first.history

        val p = FakePulsar(listOf(night, vibe("Low Tide")))
        val r = rig(store, p)
        runCurrent()

        assertTrue(r.history.items.isEmpty())
        assertEquals(night, p.vibeFlow.value)
        assertEquals(0, p.vibeLoadCount.value)
        assertTrue("Night Drive" !in store.sets)
    }

    @Test
    fun restoredVibeRowThatNoLongerFitsIsDropped() = runTest {
        val store = MemoryChangeStore()
        val first = rig(store)
        first.level(1, -4f)
        val h = first.history
        val unfit = HistoryVibeEdit(
            h.nextId, 0L, EditOrigin.USER, "arrangement.sections[0].exitEffects[0].ms", JsonPrimitive(500), JsonPrimitive(300), 0L,
        )
        store.sets["Night Drive"] = night to h.copy(items = h.items + unfit, nextId = h.nextId + 1)

        val p = FakePulsar(listOf(night, vibe("Low Tide")))
        val r = rig(store, p)
        runCurrent()

        assertTrue(r.history.vibeEdits.isEmpty())
        assertEquals(1, r.history.changes.size)
        assertEquals(night, p.vibeFlow.value)
        assertEquals(0, p.vibeLoadCount.value)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
    }

    @Test
    fun clearAllWithVibeRowsPlaysFile() = runTest {
        val r = rig()
        r.level(1, -4f)
        r.level(4, -6f)
        r.collab.collab.togglePin(4)
        r.edit(night.copy(bpm = 96f))

        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile(keep = r.collab.collab.pins.value) }
        runCurrent()

        assertTrue(r.history.vibeEdits.isEmpty())
        assertEquals(night, pulsar.vibeFlow.value)
        assertEquals(MixControl.Level(4), r.history.changes.single().control)
        assertEquals(-6f, r.controller.layers.value.vibe.levelDb[4])
        r.assertReplay()
    }

    @Test
    fun clearChangesWithVibeRowsPlaysFile() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.level(1, -4f)
        r.edit(night.copy(bpm = 96f))

        assertTrue(r.mixer.clearChanges())
        runCurrent()

        assertTrue(r.history.items.isEmpty())
        assertEquals(night, pulsar.vibeFlow.value)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])
        assertTrue("Night Drive" !in store.sets)
    }

    @Test
    fun outsideReapplyOfTheEffectiveVibeKeepsTheSet() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.collab.collab.togglePin(3)
        r.level(1, -4f)
        r.edit(night.copy(bpm = 96f))
        runCurrent()
        val set = r.history

        // As graph-ready, an engine rebuild or Mode One do: apply what is playing again.
        pulsar.applyVibe(pulsar.vibeFlow.value)
        runCurrent()

        assertEquals(set.items, r.history.items)
        assertEquals(night, r.mixer.fileVibe())
        assertEquals(night, store.sets["Night Drive"]?.first)
        assertEquals(set.items, store.sets["Night Drive"]?.second?.items)
        assertEquals(setOf(3), r.collab.collab.pins.value)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
        r.assertReplay()
    }

    @Test
    fun outsideReapplyDuringAHearKeepsTheSet() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.edit(night.copy(bpm = 96f))
        r.mixer.markPoint()
        r.edit(night.copy(bpm = 100f))
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        assertTrue(r.mixer.hear(point))
        val set = r.history

        pulsar.applyVibe(pulsar.vibeFlow.value)
        runCurrent()

        assertEquals(set.items, r.history.items)
        assertNull(r.mixer.hearing.value)
        assertEquals(100f, pulsar.vibeFlow.value.bpm)
        assertEquals(night, store.sets["Night Drive"]?.first)
    }

    @Test
    fun aVibePickedDuringTheBeforeCaptureWinsOverTheEdit() = runTest {
        val store = MemoryChangeStore()
        var pick = false
        val capture = object : MixCaptureService {
            override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>): CaptureOutcome {
                if (pick) {
                    pick = false
                    pulsar.applyVibeByName("Low Tide")
                }
                return CaptureOutcome(true, "ok", "/tmp/capture.json", emptyList(), null, aiVibe = false)
            }
        }
        val r = rig(store, capture = capture)
        r.level(4, -1f)
        r.collab.collab.beginTurn("Thin guitar")
        runCurrent()
        pick = true

        val refused = assertIs<VibeEditResult.Refused>(r.edit(night.copy(bpm = 96f), EditOrigin.AGENT))
        runCurrent()

        assertEquals(CollabMixer.NOT_PLAYING, refused.reason)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertEquals("Low Tide", r.mixer.fileVibe()?.name)
        assertTrue(r.history.items.isEmpty())
        assertTrue(store.sets["Night Drive"]?.second?.vibeEdits.orEmpty().isEmpty())
    }

    @Test
    fun aLoadRacingInBeforeAnOwnApplyKeepsTheSet() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.level(1, -4f)
        val low = pulsar.vibeList.first { it.name == "Low Tide" }
        // Another thread's load lands between the own apply's count read and its apply.
        pulsar.beforeApply = { v ->
            if (v.bpm == 96f) {
                pulsar.beforeApply = null
                pulsar.applyVibe(low)
            }
        }

        assertIs<VibeEditResult.Applied>(r.edit(night.copy(bpm = 96f)))
        runCurrent()

        assertEquals(night.copy(bpm = 96f), pulsar.vibeFlow.value)
        assertEquals(night, r.mixer.fileVibe())
        assertEquals("bpm", r.history.vibeEdits.single().path)
        assertEquals(1, r.history.changes.size)
        assertEquals(night, store.sets["Night Drive"]?.first)
        assertEquals(r.history, store.sets["Night Drive"]?.second)
        r.assertReplay()
    }

    @Test
    fun anEditRightAfterARestoringLoadStartsFromTheRestoredVibe() = runTest {
        val r = rig()
        r.level(1, -4f)
        r.edit(night.copy(bpm = 96f))
        pulsar.applyVibeByName("Low Tide")
        runCurrent()

        // No runCurrent(): the edit claims the load before the queued restore runs.
        pulsar.applyVibeByName("Night Drive")
        r.level(2, -2f)

        assertEquals(night.copy(bpm = 96f), pulsar.vibeFlow.value)
        assertEquals(listOf(0f, -4f, -2f), r.controller.layers.value.vibe.levelDb.take(3))
        val loads = r.loads()
        runCurrent()
        assertEquals(loads, r.loads(), "the queued restore found nothing left to do")
        r.assertReplay()
    }
}
