package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.mixer.f2
import org.balch.orpheus.djapp.ai.tools.CollabRig
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MemoryChangeStore
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope

@OptIn(ExperimentalCoroutinesApi::class)
class CollabHistoryTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide")))

    private class Rig(val controller: MixBenchController, val collab: CollabRig) {
        val mixer get() = collab.mixer
        val history get() = collab.mixer.history.value
    }

    private var clock = 1_000L

    private suspend fun TestScope.rig(store: MixChangeStore = NoMixChangeStore()): Rig {
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        val collab = CollabRig(pulsar, { controller }, backgroundScope, nowMs = { clock }, changeStore = store)
        runCurrent()
        return Rig(controller, collab)
    }

    private suspend fun Rig.level(t: Int, db: Float, origin: EditOrigin = EditOrigin.USER) =
        mixer.edit(origin, MixControl.Level(t)) { setHeardLevelDb(t, db) }

    private fun Rig.point() = history.items.filterIsInstance<HistoryPoint>().single().id

    private fun Rig.assertReplay() = assertEquals(history.layers(), controller.layers.value)

    @Test
    fun userEditBecomesAChangeWithShownText() = runTest {
        val r = rig()
        r.level(2, -4f)
        val c = r.history.changes.single()
        assertEquals(EditOrigin.USER, c.origin)
        assertEquals(MixControl.Level(2), c.control)
        assertEquals("0.0 dB", c.before)
        assertEquals("-4.0 dB", c.after)
        assertNull(c.section)
        assertNull(c.sectionName)
    }

    @Test
    fun sectionEditCarriesTheSectionName() = runTest {
        val r = rig()
        pulsar.section(1)
        runCurrent()
        r.mixer.edit(EditOrigin.USER, MixControl.EditScope) { setScope(Scope.SECTION) }
        r.level(2, -4f)
        val c = r.history.changes.single()
        assertEquals(1, c.section)
        assertEquals("verse", c.sectionName)
        assertEquals(setOf<LayerField>(LayerField.SectionLevel(1, 2)), c.writes.keys)
    }

    /** An agent apply as `pulsar_apply_vibe` makes it, with the DJ guard's hook first. */
    private suspend fun TestScope.agentApply(r: Rig, name: String) {
        r.mixer.keepBeforeApply(EditOrigin.AGENT)
        pulsar.applyVibe(pulsar.vibeList.first { it.name == name })
        runCurrent()
    }

    @Test
    fun anAgentApplyCanBeRolledBackWithItsMix() = runTest {
        val r = rig()
        r.level(2, -4f)
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }
        val mixBefore = r.history

        agentApply(r, "Low Tide")
        r.level(5, -2f, EditOrigin.AGENT)
        val before = assertNotNull(r.history.beforeApply)
        assertEquals("Night Drive", before.vibe.name)
        assertEquals(mixBefore, before.history)
        assertEquals(EditOrigin.AGENT, before.origin)

        assertTrue(r.mixer.rollBack(MixHistory.BEFORE_APPLY))
        runCurrent()
        assertEquals("Night Drive", pulsar.vibeFlow.value.name)
        assertEquals(mixBefore, r.history)
        r.assertReplay()
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[2])
        assertEquals(0.7f, pulsar.macroKnob(Macro.ENERGY))
    }

    @Test
    fun appliesUndoOneAtATime() = runTest {
        val r = rig()
        agentApply(r, "Low Tide")
        agentApply(r, "Night Drive")
        assertEquals("Low Tide", r.history.beforeApply?.vibe?.name)
        assertTrue(r.mixer.rollBack(MixHistory.BEFORE_APPLY))
        runCurrent()
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertEquals("Night Drive", r.history.beforeApply?.vibe?.name)
    }

    @Test
    fun aLoadWithoutAnApplyKeepsNothingAndAFailedApplyLeavesNoStaleKeep() = runTest {
        val r = rig()
        pulsar.applyVibe(pulsar.vibeList[1])
        runCurrent()
        assertNull(r.history.beforeApply)
        assertFalse(r.mixer.rollBack(MixHistory.BEFORE_APPLY))

        r.mixer.keepBeforeApply(EditOrigin.AGENT)
        r.mixer.dropBeforeApply()
        pulsar.applyVibe(pulsar.vibeList[0])
        runCurrent()
        assertNull(r.history.beforeApply)
    }

    @Test
    fun muteIsNotAChange() = runTest {
        val r = rig()
        r.mixer.edit(EditOrigin.USER, MixControl.Mute(1)) { toggleMute(1) }
        assertTrue(r.history.items.isEmpty())
    }

    @Test
    fun layersAlwaysEqualReplay() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.edit(EditOrigin.AGENT, MixControl.Pan(2)) { setPan(2, 0.4f) }
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }
        pulsar.section(0)
        runCurrent()
        r.mixer.edit(EditOrigin.USER, MixControl.EditScope) { setScope(Scope.SECTION) }
        r.mixer.edit(EditOrigin.AGENT, MixControl.Send(3, reverb = true)) { setHeardSend(3, 0.5f, reverb = true) }
        r.mixer.edit(EditOrigin.MCP, MixControl.MacroKnob(Macro.MOOD)) { setMacro(Macro.MOOD, 0.2f) }
        r.level(4, -2f, EditOrigin.AGENT)
        assertEquals(6, r.history.changes.size)
        r.assertReplay()

        assertTrue(r.mixer.toggleChange(r.history.changes[2].id))
        runCurrent()
        r.assertReplay()
        assertEquals(0.5f, pulsar.macroKnob(Macro.ENERGY))
        assertTrue(r.mixer.toggleChange(r.history.changes[0].id))
        r.assertReplay()
    }

    @Test
    fun toggleOffPlaysWithoutTheChange() = runTest {
        val r = rig()
        r.level(2, -4f)
        r.collab.collab.takeMovesForAgent()

        assertTrue(r.mixer.toggleChange(r.history.changes.single().id))

        assertEquals(0f, r.controller.layers.value.vibe.levelDb[2])
        assertFalse(r.history.changes.single().on)
        val move = r.collab.collab.takeMovesForAgent().single()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.Level(2), move.control)
        assertEquals("-4.0 dB", move.before)
        assertEquals("0.0 dB", move.after)
    }

    @Test
    fun editDuringHearEndsPreviewFirst() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.level(2, -5f)
        val point = r.point()

        assertTrue(r.mixer.hear(point))
        assertEquals(point, r.mixer.hearing.value)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[2])

        r.level(3, -2f)

        assertNull(r.mixer.hearing.value)
        assertEquals(listOf(0f, -3f, -5f, -2f), r.controller.layers.value.vibe.levelDb.take(4))
        r.assertReplay()
    }

    @Test
    fun rollBackRestoresThePoint() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.level(2, -5f)
        val point = r.point()

        assertTrue(r.mixer.rollBack(point))

        assertEquals(listOf(0f, -3f, 0f), r.controller.layers.value.vibe.levelDb.take(3))
        val (a, p) = r.history.items
        assertEquals(MixControl.Level(1), assertIs<HistoryChange>(a).control)
        assertEquals(point, assertIs<HistoryPoint>(p).id)
        assertEquals(2, r.history.items.size)
        r.assertReplay()
    }

    @Test
    fun resetKeepsPinnedChangesAndDropsPoints() = runTest {
        val r = rig()
        r.level(2, -4f)
        r.level(4, -6f)
        r.collab.collab.togglePin(4)
        r.mixer.markPoint()

        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile(keep = r.collab.collab.pins.value) }

        val c = r.history.changes.single()
        assertEquals(MixControl.Level(4), c.control)
        assertTrue(r.history.items.none { it is HistoryPoint })
        r.assertReplay()
    }

    @Test
    fun resetDropsAnOffChangeOnAnUnpinnedStrip() = runTest {
        val r = rig()
        r.level(2, -4f)
        assertTrue(r.mixer.toggleChange(r.history.changes.single().id))

        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile(keep = r.collab.collab.pins.value) }

        assertTrue(r.history.changes.isEmpty())
        r.assertReplay()
    }

    @Test
    fun resetKeepsAnOffChangeOnAPinnedStrip() = runTest {
        val r = rig()
        r.level(2, -4f)
        assertTrue(r.mixer.toggleChange(r.history.changes.single().id))
        r.collab.collab.togglePin(2)

        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile(keep = r.collab.collab.pins.value) }

        assertFalse(r.history.changes.single().on)
        r.assertReplay()
    }

    @Test
    fun macroRowBeforeIsWhatTurningItOffPlays() = runTest {
        val r = rig()
        val file = pulsar.vibeFlow.value.mood
        // A main-screen move: the knob moves, the bench layers do not.
        pulsar.actions.setMood(0.2f)
        runCurrent()

        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.MOOD)) { setMacro(Macro.MOOD, 0.7f) }

        val c = r.history.changes.single()
        assertEquals(f2(file), c.before)
        assertEquals(f2(0.7f), c.after)
        assertTrue(r.mixer.toggleChange(c.id))
        assertEquals(file, pulsar.macroKnob(Macro.MOOD))
    }

    @Test
    fun macroRowBeforeIsTheEarlierMixerValue() = runTest {
        val r = rig()
        val start = pulsar.macroKnob(Macro.MOOD)
        val startShown = f2(start)
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.MOOD)) { setMacro(Macro.MOOD, 0.3f) }
        clock += 10_000L
        pulsar.actions.setMood(0.2f)
        runCurrent()

        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.MOOD)) { setMacro(Macro.MOOD, 0.7f) }

        // The second edit folds into the first row, which keeps the first before.
        val c = r.history.changes.single()
        assertEquals(startShown, c.before)
        assertTrue(r.mixer.toggleChange(c.id))
        assertEquals(start, pulsar.macroKnob(Macro.MOOD))
    }

    @Test
    fun hearFilePlaysTheVibeAsLoaded() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }
        val file = pulsar.vibeFlow.value.energy

        assertTrue(r.mixer.hear(MixHistory.FILE))

        assertEquals(MixHistory.FILE, r.mixer.hearing.value)
        assertEquals(BenchLayers(), r.controller.layers.value)
        assertEquals(file, pulsar.macroKnob(Macro.ENERGY))

        r.mixer.endHear()

        assertNull(r.mixer.hearing.value)
        assertEquals(-3f, r.controller.layers.value.vibe.levelDb[1])
        assertEquals(0.7f, pulsar.macroKnob(Macro.ENERGY))
        assertEquals(2, r.history.changes.size)
        r.assertReplay()
    }

    @Test
    fun rollBackToFileLeavesNoChanges() = runTest {
        val r = rig()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }

        assertTrue(r.mixer.rollBack(MixHistory.FILE))

        assertTrue(r.history.items.isEmpty())
        assertEquals(BenchLayers(), r.controller.layers.value)
        assertEquals(pulsar.vibeFlow.value.energy, pulsar.macroKnob(Macro.ENERGY))
        r.assertReplay()
    }

    @Test
    fun vibeLoadClearsHistory() = runTest {
        val r = rig()
        r.level(1, -3f)
        pulsar.vibeLoadCount.value++
        runCurrent()

        r.level(2, -5f)

        assertEquals(MixControl.Level(2), r.history.items.map { assertIs<HistoryChange>(it).control }.single())
        r.assertReplay()
    }

    @Test
    fun reloadOfTheSameVibeRestampsLoadedAt() = runTest {
        val r = rig()
        assertEquals(1_000L, r.history.loadedAtMs)
        clock = 9_000L
        pulsar.vibeLoadCount.value++
        runCurrent()
        assertEquals(9_000L, r.history.loadedAtMs)
        clock = 12_000L
        r.level(1, -3f)
        assertEquals(9_000L, r.history.loadedAtMs)
    }

    @Test
    fun endHearAfterAVibeLoadAppliesNothingStale() = runTest {
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        // A cancelled scope: the mixer's load follower never runs, so only endHear can claim the load.
        val r = Rig(controller, CollabRig(pulsar, { controller }, CoroutineScope(Job().apply { cancel() })))
        runCurrent()
        r.level(1, -3f)
        r.mixer.markPoint()
        r.level(2, -5f)
        assertTrue(r.mixer.hear(r.point()))
        pulsar.vibeLoadCount.value++
        runCurrent()

        r.mixer.endHear()

        assertNull(r.mixer.hearing.value)
        assertTrue(r.history.items.isEmpty())
        assertEquals(BenchLayers(), controller.layers.value)
        r.assertReplay()
    }

    @Test
    fun toggleDuringLoadInFlightChangesNothing() = runTest {
        val r = rig()
        r.level(1, -3f)
        val before = r.history
        pulsar.vibeLoadCount.value++

        assertFalse(r.mixer.toggleChange(before.changes.single().id))

        assertEquals(before, r.history)
    }

    private fun Rig.pointLabels() = history.items.filterIsInstance<HistoryPoint>().map { it.label }

    private fun Rig.captureLabels() =
        history.items.filterIsInstance<HistoryPoint>().filter { it.kind == PointKind.CAPTURE }.map { it.label }

    private suspend fun TestScope.agentTurn(r: Rig, label: String, block: suspend () -> Unit) {
        r.collab.collab.beginTurn(label)
        // A real turn outlasts the collector, which must see it begin.
        runCurrent()
        block()
        r.collab.collab.endTurn()
        runCurrent()
    }

    @Test
    fun agentTurnBracketsChangesWithCaptures() = runTest {
        val r = rig()
        r.level(4, -1f)
        agentTurn(r, "Pull the pads back") {
            r.level(1, -3f, EditOrigin.AGENT)
            r.mixer.edit(EditOrigin.AGENT, MixControl.Pan(2)) { setPan(2, 0.4f) }
        }
        val kinds = r.history.items.map { item ->
            when (item) {
                is HistoryPoint -> "${item.kind} ${item.label}"
                is HistoryChange -> "change ${item.origin}"
            }
        }
        assertEquals(
            listOf("change USER", "CAPTURE before: Pull the pads back", "change AGENT", "change AGENT", "CAPTURE after: Pull the pads back"),
            kinds,
        )
        assertEquals(2, r.collab.capture.calls)
    }

    @Test
    fun queuedTurnsEachGetTheirOwnCaptures() = runTest {
        val r = rig()
        val c = r.collab.collab
        c.beginTurn("A")
        runCurrent()
        r.level(1, -3f, EditOrigin.AGENT)
        // As DjAiChat queues the next turn: no suspension between the two.
        c.endTurn()
        c.beginTurn("B")
        r.level(2, -3f, EditOrigin.AGENT)
        runCurrent()
        c.endTurn()
        runCurrent()
        val kinds = r.history.items.map { if (it is HistoryPoint) it.label else "change" }
        // "after: A" is the newest row, so B needs no before-capture.
        assertEquals(listOf("change", "after: A", "change", "after: B"), kinds)
    }

    @Test
    fun aTurnWithoutALabelStillCaptures() = runTest {
        val r = rig()
        r.level(4, -1f)
        r.collab.collab.beginTurn(null)
        runCurrent()
        r.level(1, -3f, EditOrigin.AGENT)
        r.collab.collab.endTurn()
        runCurrent()
        assertEquals(listOf("before: song reference", "after: song reference"), r.captureLabels())
    }

    @Test
    fun noBeforeCaptureWhenNewestIsCapture() = runTest {
        val r = rig()
        r.level(4, -1f)
        r.mixer.capture()
        agentTurn(r, "Brighter") { r.level(1, -3f, EditOrigin.AGENT) }
        assertEquals(listOf(CollabMixer.CAPTURED, "after: Brighter"), r.captureLabels())
    }

    @Test
    fun noBeforeCaptureOnCleanHistory() = runTest {
        val r = rig()
        agentTurn(r, "Brighter") { r.level(1, -3f, EditOrigin.AGENT) }
        assertEquals(listOf("after: Brighter"), r.captureLabels())
    }

    @Test
    fun turnWithoutNetChangeAddsNoAfterCapture() = runTest {
        val r = rig()
        r.level(4, -1f)
        agentTurn(r, "Nothing") { r.level(1, 0f, EditOrigin.AGENT) }
        assertEquals(listOf("before: Nothing"), r.captureLabels())
    }

    @Test
    fun mcpEditsNeverAutoCapture() = runTest {
        val r = rig()
        r.level(4, -1f)
        agentTurn(r, "Pull the pads back") { r.level(1, -3f, EditOrigin.MCP) }
        assertTrue(r.captureLabels().isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun beforeApplyRollbackKeepsEachVibesStore() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.level(2, -4f)
        val aSet = r.history

        agentApply(r, "Low Tide")
        r.level(5, -2f)
        val bSet = r.history

        assertTrue(r.mixer.rollBack(MixHistory.BEFORE_APPLY))
        runCurrent()
        assertEquals("Night Drive", pulsar.vibeFlow.value.name)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[2])
        assertEquals(aSet, store.sets["Night Drive"]?.second)
        assertEquals(bSet, store.sets["Low Tide"]?.second)
    }

    @Test
    fun aTurnThatAppliesThenMixesCapturesOnTheNewVibe() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.level(2, -4f)
        agentTurn(r, "Swap and fix") {
            agentApply(r, "Low Tide")
            r.level(5, -2f, EditOrigin.AGENT)
        }
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        val newest = assertIs<HistoryPoint>(r.history.items.last())
        assertEquals(PointKind.CAPTURE, newest.kind)
        assertEquals("after: Swap and fix", newest.label)
        val old = assertNotNull(store.sets["Night Drive"]).second
        assertTrue(old.items.none { it is HistoryPoint && it.kind == PointKind.CAPTURE })
    }

    @Test
    fun aTurnThatLeavesForAnotherVibeWritesNoAfterOnIt() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        // Low Tide keeps an uncaptured user row in the store.
        agentApply(r, "Low Tide")
        r.level(3, -5f)
        agentApply(r, "Night Drive")
        agentTurn(r, "Then leave") {
            r.level(1, -3f, EditOrigin.AGENT)
            agentApply(r, "Low Tide")
        }
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertEquals(MixControl.Level(3), r.history.changes.single().control)
        assertTrue(r.captureLabels().none { it.startsWith("after:") })
    }

    @Test
    fun userEditsInATurnDropNoPoint() = runTest {
        val r = rig()
        r.collab.collab.beginTurn("Pull the pads back")
        r.level(1, -3f)
        r.level(2, -3f, EditOrigin.MCP)
        assertTrue(r.pointLabels().isEmpty())
    }

    @Test
    fun longLabelsAreCutAtAWord() = runTest {
        val r = rig()
        r.collab.collab.setMixerVisible(true)
        r.collab.collab.beginTurn("Make the pads wash over everything and the drums duck underneath")
        r.level(4, -1f)
        r.level(1, -3f, EditOrigin.AGENT)
        val label = r.pointLabels().single()
        assertEquals("before: Make the pads wash over everything and…", label)
        assertTrue(label.length <= 48)
    }

    @Test
    fun newlinesInTheLabelBecomeSpaces() = runTest {
        val r = rig()
        r.collab.collab.setMixerVisible(true)
        r.collab.collab.beginTurn("Pads down\nbass up")
        r.level(4, -1f)
        r.level(1, -3f, EditOrigin.AGENT)
        assertEquals(listOf("before: Pads down bass up"), r.pointLabels())
    }
}
