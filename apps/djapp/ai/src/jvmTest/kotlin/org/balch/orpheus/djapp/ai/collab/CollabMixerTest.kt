package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MemoryChangeStore
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope

@OptIn(ExperimentalCoroutinesApi::class)
class CollabMixerTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }

    private object NoTap : MixBenchTap {
        override fun setEnabled(on: Boolean) {}
        override fun drain(out: FloatArray) = 0
    }

    private class Rig(
        val pulsar: FakePulsar,
        val controller: MixBenchController,
        val collab: MixCollab,
        val mixer: CollabMixer,
        val moves: List<MixMove>,
    )

    private val night = vibe("Night Drive")

    private suspend fun TestScope.rig(store: MixChangeStore = NoMixChangeStore()): Rig {
        val pulsar = FakePulsar(listOf(night, vibe("Low Tide").copy(energy = 0.9f)))
        val controller = MixBenchController(pulsar, synth, NoTap, backgroundScope).also { it.start() }
        val collab = MixCollab().apply { setMixerVisible(true) }
        val mixer = CollabMixer({ controller }, { pulsar }, collab, backgroundScope, changeStore = { store })
        val moves = mutableListOf<MixMove>()
        backgroundScope.launch { collab.moves.collect { moves += it } }
        runCurrent()
        return Rig(pulsar, controller, collab, mixer, moves)
    }

    @Test
    fun userEditRecordsBeforeAndAfter() = runTest {
        val r = rig()
        r.controller.setHeardLevelDb(0, -2f)
        val result = r.mixer.edit(EditOrigin.USER, MixControl.Level(0)) { setHeardLevelDb(0, -5f) }
        runCurrent()
        assertEquals(CollabResult.Done(true), result)
        val move = r.moves.single()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.Level(0), move.control)
        assertEquals("-2.0 dB", move.before)
        assertEquals("-5.0 dB", move.after)
    }

    @Test
    fun refusedEditDoesNotTouchController() = runTest {
        val r = rig()
        r.collab.setMixerVisible(false)
        var ran = false
        val result = r.mixer.edit(EditOrigin.AGENT, MixControl.Level(0)) { ran = true; setHeardLevelDb(0, -5f) }
        runCurrent()
        val refused = assertIs<CollabResult.Refused>(result)
        assertEquals(Refusal.MIXER_CLOSED, refused.refusal)
        assertEquals("The Mixer is closed. Ask the user to open it; don't change the mix until they do.", refused.reason)
        assertTrue(!ran)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[0])
        assertTrue(r.moves.isEmpty())

        r.collab.setMixerVisible(true)
        r.collab.togglePin(0)
        val pinned = assertIs<CollabResult.Refused>(r.mixer.edit(EditOrigin.MCP, MixControl.Pan(0)) { setPan(0, 0.5f) })
        assertEquals("The user pinned this strip. Ask before changing it.", pinned.reason)
        assertNull(r.controller.layers.value.vibe.pan[0])
    }

    @Test
    fun userMovedReasonIsExact() = runTest {
        val r = rig()
        r.collab.beginTurn()
        r.mixer.edit(EditOrigin.USER, MixControl.Mute(1)) { toggleMute(1) }
        val refused = assertIs<CollabResult.Refused>(r.mixer.edit(EditOrigin.AGENT, MixControl.Mute(1)) { toggleMute(1) })
        assertEquals(Refusal.USER_MOVED, refused.refusal)
        assertEquals("The user moved this control during your turn, so their value stands.", refused.reason)
    }

    @Test
    fun anEditTheControllerDidNotApplyIsNotRecorded() = runTest {
        val r = rig()
        r.mixer.edit(EditOrigin.USER, MixControl.Mute(1)) { false }
        runCurrent()
        assertTrue(r.moves.isEmpty())
    }

    @Test
    fun editsAreShownTheWayTheMixerShowsThem() = runTest {
        val r = rig()
        r.pulsar.section(0)
        r.mixer.edit(EditOrigin.USER, MixControl.Pan(2)) { setPan(2, -0.3f) }
        r.mixer.edit(EditOrigin.USER, MixControl.Mute(2)) { toggleMute(2) }
        r.mixer.edit(EditOrigin.USER, MixControl.Loop) { setLoop(true) }
        r.mixer.edit(EditOrigin.USER, MixControl.EditScope) { setScope(Scope.SECTION) }
        r.mixer.edit(EditOrigin.USER, MixControl.Section, shown = "chorus") { jumpTo(2) }
        runCurrent()
        assertEquals(
            listOf("C" to "L30", "off" to "on", "off" to "on", "whole vibe" to "section", "intro" to "chorus"),
            r.moves.map { it.before to it.after },
        )
    }

    @Test
    fun knobMovedOutsideMixerIsAUserMove() = runTest {
        val r = rig()
        r.pulsar.actions.setEnergy(0.8f)
        runCurrent()
        val move = r.moves.single()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.MacroKnob(Macro.ENERGY), move.control)
        assertEquals("0.50", move.before)
        assertEquals("0.80", move.after)
    }

    @Test
    fun ownMacroEditIsNotDoubleRecorded() = runTest {
        val r = rig()
        r.mixer.edit(EditOrigin.USER, MixControl.MacroKnob(Macro.ENERGY)) { setMacro(Macro.ENERGY, 0.7f) }
        advanceUntilIdle()
        runCurrent()
        val move = r.moves.single()
        assertEquals("0.50", move.before)
        assertEquals("0.70", move.after)

        // The same value again from the knob is a real move once the knob has left it.
        r.pulsar.actions.setEnergy(0.2f)
        runCurrent()
        r.pulsar.actions.setEnergy(0.7f)
        runCurrent()
        assertEquals(listOf("0.70", "0.20", "0.70"), r.moves.map { it.after })
    }

    @Test
    fun aResetMovingKnobsBackIsOneMoveNotTheUsersKnobMoves() = runTest {
        val r = rig()
        r.pulsar.actions.setEnergy(0.8f)
        runCurrent()
        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile() }
        advanceUntilIdle()
        runCurrent()
        assertEquals(0.5f, r.pulsar.macroKnob(Macro.ENERGY))
        assertEquals(listOf(MixControl.MacroKnob(Macro.ENERGY), MixControl.ClearAll), r.moves.map { it.control })
        assertTrue(r.collab.userChanged.value.isEmpty())
    }

    @Test
    fun knobWritesThatFollowAVibeLoadAreNotTheUsers() = runTest {
        val r = rig()
        r.pulsar.applyVibeByName("Low Tide")
        runCurrent()
        // The load writes the new vibe's knob after it bumps the count.
        r.pulsar.actions.setEnergy(0.9f)
        runCurrent()
        assertTrue(r.moves.isEmpty())
        r.pulsar.actions.setEnergy(0.3f)
        runCurrent()
        assertEquals(listOf("0.30"), r.moves.map { it.after })
    }

    @Test
    fun vibeLoadClearsTurnState() = runTest {
        val r = rig()
        r.collab.togglePin(2)
        r.collab.beginTurn()
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        assertEquals(Refusal.USER_MOVED, r.collab.check(EditOrigin.AGENT, MixControl.Level(1)))
        assertEquals(Refusal.PINNED, r.collab.check(EditOrigin.AGENT, MixControl.Level(2)))

        r.pulsar.applyVibeByName("Low Tide")
        runCurrent()

        assertTrue(r.collab.pins.value.isEmpty())
        assertNull(r.collab.check(EditOrigin.AGENT, MixControl.Level(1)))
        assertNull(r.collab.check(EditOrigin.AGENT, MixControl.Level(2)))
    }

    @Test
    fun anAgentEditRightAfterAVibeLoadIsNotRefusedForTheOldVibe() = runTest {
        val r = rig()
        r.collab.togglePin(2)
        r.collab.beginTurn()
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }

        // No runCurrent(): the follower has not seen the load, so the edit itself must.
        r.pulsar.applyVibeByName("Low Tide")
        val moved = r.mixer.edit(EditOrigin.AGENT, MixControl.Level(1)) { setHeardLevelDb(1, -1f) }
        val pinned = r.mixer.edit(EditOrigin.MCP, MixControl.Level(2)) { setHeardLevelDb(2, -1f) }

        assertIs<CollabResult.Done<*>>(moved)
        assertIs<CollabResult.Done<*>>(pinned)
        // The controller itself still holds edits until it follows the load, so nothing was written yet; the point is the check.
        runCurrent()
        assertTrue(r.collab.pins.value.isEmpty())
        // A pin made on the new vibe after the load survives the follower reaching the same load.
        r.collab.togglePin(3)
        runCurrent()
        assertEquals(setOf(3), r.collab.pins.value)
    }

    @Test
    fun changeSetSurvivesVibeSwitch() = runTest {
        val r = rig(MemoryChangeStore())
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        r.mixer.markPoint()
        val saved = r.mixer.history.value.items
        r.pulsar.applyVibeByName("Low Tide")
        runCurrent()
        assertTrue(r.mixer.history.value.items.isEmpty())
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])

        r.pulsar.applyVibeByName("Night Drive")
        runCurrent()
        assertEquals(saved, r.mixer.history.value.items)
        assertEquals(1, r.mixer.history.value.pointCount)
        assertEquals(r.mixer.history.value.layers(), r.controller.layers.value)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
    }

    @Test
    fun restoredChangeSetReplaysAfterLoadFollows() = runTest {
        val store = MemoryChangeStore()
        val first = rig(store)
        first.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        val saved = first.mixer.history.value.items

        // A relaunch: a new mixer restores the set at its first claim and replays it once the controller follows.
        val r = rig(store)
        runCurrent()
        assertEquals(saved, r.mixer.history.value.items)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])

        // An edit that claims the load first plays the restored set before its own block.
        r.pulsar.applyVibeByName("Low Tide")
        runCurrent()
        r.pulsar.applyVibeByName("Night Drive")
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -6f) }
        r.mixer.edit(EditOrigin.USER, MixControl.Level(2)) { setHeardLevelDb(2, -2f) }
        runCurrent()
        assertEquals("-4.0 dB", r.moves.first { it.after == "-6.0 dB" }.before)
        assertEquals(listOf(MixControl.Level(1), MixControl.Level(2)), r.mixer.history.value.changes.map { it.control })
        assertEquals(r.mixer.history.value.layers(), r.controller.layers.value)
        assertEquals(-6f, r.controller.layers.value.vibe.levelDb[1])
        assertEquals(-2f, r.controller.layers.value.vibe.levelDb[2])
    }

    @Test
    fun changedVibeStartsEmpty() = runTest {
        val store = MemoryChangeStore()
        val first = rig(store)
        first.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        // The vibe was edited since: the saved set is for the old content.
        store.sets["Night Drive"] = night.copy(bpm = 96f) to first.mixer.history.value

        val r = rig(store)
        runCurrent()
        assertTrue(r.mixer.history.value.items.isEmpty())
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])
        assertTrue("Night Drive" !in store.sets)
    }

    @Test
    fun clearAllKeepsPinnedTracks() = runTest {
        val r = rig(MemoryChangeStore())
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        r.mixer.edit(EditOrigin.USER, MixControl.Level(4)) { setHeardLevelDb(4, -6f) }
        r.collab.togglePin(4)
        r.mixer.edit(EditOrigin.USER, MixControl.ClearAll, shown = "file") { resetToFile(keep = r.collab.pins.value) }
        assertEquals(listOf(MixControl.Level(4)), r.mixer.history.value.changes.map { it.control })
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])
        assertEquals(-6f, r.controller.layers.value.vibe.levelDb[4])
    }

    @Test
    fun clearChangesIgnoresPins() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        r.mixer.edit(EditOrigin.USER, MixControl.Level(4)) { setHeardLevelDb(4, -6f) }
        r.collab.togglePin(4)
        r.mixer.markPoint()
        assertTrue(r.mixer.hear(MixHistory.FILE))

        assertTrue(r.mixer.clearChanges())
        assertTrue(r.mixer.history.value.items.isEmpty())
        assertNull(r.mixer.hearing.value)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[4])
        assertTrue("Night Drive" !in store.sets)
        assertEquals(setOf(4), r.collab.pins.value, "pins are left alone")
    }

    @Test
    fun clearChangesForOtherVibeRemovesStoreOnly() = runTest {
        val store = MemoryChangeStore()
        val r = rig(store)
        r.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        r.pulsar.applyVibeByName("Low Tide")
        runCurrent()
        r.mixer.edit(EditOrigin.USER, MixControl.Level(2)) { setHeardLevelDb(2, -2f) }
        assertTrue("Night Drive" in store.sets)

        assertTrue(r.mixer.clearChanges("Night Drive"))
        assertTrue("Night Drive" !in store.sets)
        assertEquals(1, r.mixer.history.value.changes.size, "the playing vibe's set stays")
        assertEquals(-2f, r.controller.layers.value.vibe.levelDb[2])
    }

    @Test
    fun toolOriginIsMcpUnderExempt() = runTest {
        val r = rig()
        assertEquals(EditOrigin.AGENT, r.mixer.toolOrigin())
        assertEquals(EditOrigin.MCP, withContext(TurnBudget.Exempt) { r.mixer.toolOrigin() })
    }
}
