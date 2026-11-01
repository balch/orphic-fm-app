package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.tools.CollabRig
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MemoryChangeStore
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange
import org.balch.orpheus.features.pulsar.bench.enginePath
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.Vibe

@OptIn(ExperimentalCoroutinesApi::class)
class CollabEngineKnobTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val night = vibe("Night Drive")
    private val tide = vibe("Low Tide")

    private class Rig(val pulsar: FakePulsar, val collab: CollabRig) {
        val mixer get() = collab.mixer
        val history get() = collab.mixer.history.value
        val loads get() = pulsar.vibeLoadCount.value
        val edm2 get() = pulsar.vibeFlow.value.tracks[2].engineEdm
    }

    private suspend fun TestScope.rig(
        store: MixChangeStore = MemoryChangeStore(),
        p: FakePulsar = FakePulsar(listOf(night, tide)).apply { retunes = true },
        capture: MixCaptureService? = null,
    ): Rig {
        val controller = MixBenchController(p, synth, NoMeterTap, backgroundScope).also { it.start() }
        val collab = if (capture == null) {
            CollabRig(p, { controller }, backgroundScope, changeStore = store)
        } else {
            CollabRig(p, { controller }, backgroundScope, changeStore = store, captureService = { capture })
        }
        runCurrent()
        return Rig(p, collab)
    }

    private suspend fun Rig.turn(value: Float?, knob: EngineKnob = EngineKnob.HARMONICS, slot: EngineSlot = EngineSlot.EDM, track: Int = 2) =
        mixer.editEngineKnob(EditOrigin.USER, track, slot, knob, value)

    private fun Rig.paths() = history.vibeEdits.map { it.path }

    @Test
    fun aTurnPinsTheKnobAndThePlayingSongPlaysOn() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        val loads = r.loads

        val result = assertIs<VibeEditResult.Applied>(r.turn(0.3f))
        runCurrent()

        assertEquals(2, result.rows)
        assertEquals(false, result.restarted)
        assertEquals(loads, r.loads, "a knob turn retunes; it never reloads the vibe")
        assertEquals(1, r.pulsar.retuneCount)
        assertEquals(0.3f, r.edm2.harmonics)
        assertTrue(r.edm2.pinHarmonics)
        assertEquals(listOf("tracks[2].engineEdm.harmonics", "tracks[2].engineEdm.pinHarmonics"), r.paths().sorted())
        // The change set is saved under the file, as every other row is.
        val (saved, set) = assertNotNull(store.sets["Night Drive"])
        assertEquals(night, saved)
        assertEquals(r.history, set)
    }

    @Test
    fun aDragFoldsIntoOneRowPerField() = runTest {
        val r = rig()
        for (v in listOf(0.31f, 0.35f, 0.42f)) r.turn(v)
        assertEquals(2, r.history.vibeEdits.size)
        val row = r.history.vibeEdits.single { it.path.endsWith(".harmonics") }
        assertEquals(JsonPrimitive(0.42f), row.after)
        assertEquals(0.42f, r.edm2.harmonics)
    }

    @Test
    fun aResetPutsKnobAndPinBackToTheFile() = runTest {
        val r = rig()
        r.turn(0.3f)
        val loads = r.loads
        r.turn(null)
        assertEquals(emptyList(), r.paths())
        assertEquals(night, r.pulsar.vibeFlow.value)
        assertEquals(loads, r.loads)
    }

    @Test
    fun eachSlotIsItsOwnRow() = runTest {
        val r = rig()
        r.turn(0.7f, EngineKnob.MORPH, EngineSlot.SPACE)
        assertEquals(listOf("tracks[2].engineSpace.morph", "tracks[2].engineSpace.pinMorph"), r.paths().sorted())
        assertEquals(night.tracks[2].engineEdm, r.edm2, "the EDM engine is left alone")
        assertEquals(0.7f, r.pulsar.vibeFlow.value.tracks[2].engineSpace.morph)
    }

    @Test
    fun dxHarmonicsSnapsToItsPatchAndNeedsNoPinRow() = runTest {
        val dx = night.copy(
            tracks = night.tracks.toMutableList().also {
                it[2] = it[2].copy(engineEdm = OrpheusEngine(engineId = OrpheusEngineId.DX))
            },
        )
        val r = rig(p = FakePulsar(listOf(dx, tide)).apply { retunes = true })
        r.turn(0.5f)
        // Patch 16 of 32: floor(0.5 * 32.64) = 16, written at its middle.
        assertEquals(16.5f / 32.64f, r.edm2.harmonics, 1e-6f)
        assertEquals(listOf("tracks[2].engineEdm.harmonics"), r.paths())
    }

    @Test
    fun turningARowOffRetunesToo() = runTest {
        val r = rig()
        r.turn(0.3f)
        val loads = r.loads
        val row = r.history.vibeEdits.first { it.path.endsWith(".harmonics") }
        assertTrue(r.mixer.toggleChange(row.id))
        assertEquals(night.tracks[2].engineEdm.harmonics, r.edm2.harmonics)
        assertTrue(r.edm2.pinHarmonics, "the pin row is still on")
        assertEquals(loads, r.loads)
    }

    @Test
    fun knobRowsComeBackWhenTheVibeLoadsAgain() = runTest {
        val r = rig()
        r.turn(0.3f)
        r.pulsar.applyVibe(tide)
        runCurrent()
        r.mixer.fileVibe()
        r.pulsar.applyVibe(night)
        runCurrent()
        // The restored rows play over the file in place: no second load after the user's.
        assertEquals(0.3f, r.mixer.effectiveVibe()!!.tracks[2].engineEdm.harmonics)
        runCurrent()
        assertEquals(0.3f, r.edm2.harmonics)
        assertEquals(2, r.loads)
        assertEquals(2, r.history.vibeEdits.size)
    }

    @Test
    fun withoutALiveRetuneTheVibeReloads() = runTest {
        val r = rig(p = FakePulsar(listOf(night, tide)))
        val loads = r.loads
        val result = assertIs<VibeEditResult.Applied>(r.turn(0.3f))
        assertEquals(true, result.restarted)
        assertEquals(loads + 1, r.loads)
        assertEquals(0.3f, r.edm2.harmonics)
    }

    @Test
    fun captureCarriesTheKnobRows() = runTest {
        var rows: List<VibeFieldChange> = emptyList()
        val capture = object : MixCaptureService {
            override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>): CaptureOutcome {
                rows = vibeRows
                return CaptureOutcome(true, "ok", "/tmp/capture.json", emptyList(), null, aiVibe = false)
            }
        }
        val r = rig(capture = capture)
        r.turn(0.3f, EngineKnob.TIMBRE)
        assertNotNull(r.mixer.capture())
        assertEquals(listOf("tracks[2].engineEdm.pinTimbre", "tracks[2].engineEdm.timbre"), rows.map { it.path }.sorted())
        assertEquals(JsonPrimitive(0.3f), rows.single { it.path.endsWith(".timbre") }.new)
    }

    private val engine4 = enginePath(4, EngineSlot.EDM)
    private val Rig.engine4Id get() = pulsar.vibeFlow.value.tracks[4].engineEdm.engineId

    private suspend fun Rig.switchEngine(): VibeEditResult =
        mixer.editVibe(EditOrigin.USER, listOf(engine4 to JsonPrimitive("VOWEL_FOF")))

    @Test
    fun anEngineEditPlaysOn() = runTest {
        val r = rig()
        val loads = r.loads

        val result = assertIs<VibeEditResult.Applied>(r.switchEngine())
        runCurrent()

        assertEquals(false, result.restarted)
        assertEquals(loads, r.loads)
        assertEquals(OrpheusEngineId.VOWEL_FOF, r.engine4Id)
        assertEquals(listOf(engine4), r.paths())
        assertEquals(1, r.history.items.count { it is HistoryVibeEdit })
    }

    @Test
    fun togglingAndRollingBackAnEngineRowPlayOn() = runTest {
        val r = rig()
        r.mixer.markPoint()
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        r.switchEngine()
        val loads = r.loads
        val row = r.history.vibeEdits.single()

        assertTrue(r.mixer.toggleChange(row.id))
        assertEquals(night.tracks[4].engineEdm.engineId, r.engine4Id)
        assertEquals(loads, r.loads)

        assertTrue(r.mixer.toggleChange(row.id))
        assertEquals(OrpheusEngineId.VOWEL_FOF, r.engine4Id)
        assertEquals(loads, r.loads)

        assertTrue(r.mixer.rollBack(point))
        assertEquals(night.tracks[4].engineEdm.engineId, r.engine4Id)
        assertEquals(loads, r.loads)
    }

    @Test
    fun hearingAPointBeforeAnEngineRowPlaysOn() = runTest {
        val r = rig()
        r.mixer.markPoint()
        val point = r.history.items.filterIsInstance<HistoryPoint>().single().id
        r.switchEngine()
        val loads = r.loads

        assertTrue(r.mixer.hear(point))
        runCurrent()
        assertEquals(night.tracks[4].engineEdm.engineId, r.engine4Id)
        assertEquals(loads, r.loads)

        r.mixer.endHear()
        runCurrent()
        assertEquals(OrpheusEngineId.VOWEL_FOF, r.engine4Id)
        assertEquals(loads, r.loads)
    }

    @Test
    fun anEngineAndBpmEditRestarts() = runTest {
        val r = rig()
        val loads = r.loads
        val result = assertIs<VibeEditResult.Applied>(
            r.mixer.editVibe(EditOrigin.USER, listOf(engine4 to JsonPrimitive("VOWEL_FOF"), "bpm" to JsonPrimitive(96))),
        )
        runCurrent()
        assertEquals(true, result.restarted)
        assertEquals(loads + 1, r.loads)
        assertEquals(OrpheusEngineId.VOWEL_FOF, r.engine4Id)
        assertEquals(96f, r.pulsar.vibeFlow.value.bpm)
    }
}
