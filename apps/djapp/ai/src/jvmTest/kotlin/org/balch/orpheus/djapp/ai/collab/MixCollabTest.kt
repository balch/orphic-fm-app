package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.features.pulsar.bench.Macro

class MixCollabTest {
    private var now = 1_000L
    private val collab = MixCollab { now }.apply { setMixerVisible(true) }

    @Test
    fun userIsNeverRefused() {
        collab.setMixerVisible(false)
        collab.togglePin(3)
        collab.beginTurn()
        collab.record(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-3.0 dB")
        assertNull(collab.check(EditOrigin.USER, MixControl.Level(3)))
        assertNull(collab.check(EditOrigin.USER, MixControl.Section))
    }

    @Test
    fun agentRefusedWhenMixerClosed() {
        collab.setMixerVisible(false)
        assertEquals(Refusal.MIXER_CLOSED, collab.check(EditOrigin.AGENT, MixControl.Level(3)))
        assertEquals(Refusal.MIXER_CLOSED, collab.check(EditOrigin.AGENT, MixControl.Section))
        assertEquals(Refusal.MIXER_CLOSED, collab.check(EditOrigin.AGENT, MixControl.MacroKnob(Macro.ENERGY)))
        collab.setMixerVisible(true)
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(3)))
    }

    @Test
    fun agentRefusedOnPinnedTrack() {
        collab.togglePin(2)
        assertEquals(Refusal.PINNED, collab.check(EditOrigin.AGENT, MixControl.Send(2, reverb = true)))
        assertEquals(Refusal.PINNED, collab.check(EditOrigin.AGENT, MixControl.Mute(2)))
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(1)))
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Loop))
        collab.togglePin(2)
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Mute(2)))
    }

    @Test
    fun mcpIgnoresVisibilityButRespectsPins() {
        collab.setMixerVisible(false)
        assertNull(collab.check(EditOrigin.MCP, MixControl.Level(3)))
        collab.togglePin(3)
        assertEquals(Refusal.PINNED, collab.check(EditOrigin.MCP, MixControl.Level(3)))
        assertNull(collab.check(EditOrigin.MCP, MixControl.Section))
    }

    @Test
    fun mcpIsNotHeldOffByUserMovesDuringATurn() {
        collab.beginTurn()
        collab.record(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-3.0 dB")
        assertNull(collab.check(EditOrigin.MCP, MixControl.Level(3)))
    }

    @Test
    fun agentSkipsControlUserMovedThisTurn() {
        collab.record(EditOrigin.USER, MixControl.Level(3), "0.0 dB", "-1.0 dB") // before the turn
        collab.beginTurn()
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(3)))
        collab.record(EditOrigin.USER, MixControl.Level(3), "-1.0 dB", "-2.0 dB")
        assertEquals(Refusal.USER_MOVED, collab.check(EditOrigin.AGENT, MixControl.Level(3)))
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(4)))
        collab.record(EditOrigin.USER, MixControl.Section, "A", "B")
        assertEquals(Refusal.USER_MOVED, collab.check(EditOrigin.AGENT, MixControl.Section))
        collab.endTurn()
        collab.beginTurn()
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(3)))
    }

    @Test
    fun coalescesADragIntoOneMove() {
        repeat(200) { i ->
            now += 5
            val before = if (i == 0) "-2.0 dB" else "step $i"
            val after = if (i == 199) "-5.0 dB" else "step ${i + 1}"
            collab.record(EditOrigin.USER, MixControl.Level(3), before, after)
        }
        val moves = collab.takeMovesForAgent()
        assertEquals(1, moves.size)
        assertEquals(EditOrigin.USER, moves[0].origin)
        assertEquals("-2.0 dB", moves[0].before)
        assertEquals("-5.0 dB", moves[0].after)
        assertEquals(1_000L + 200 * 5, moves[0].atMs)
    }

    @Test
    fun coalescesNonConsecutiveMovesAndKeepsOldestFirst() {
        collab.record(EditOrigin.USER, MixControl.Level(1), "a", "b")
        collab.record(EditOrigin.MCP, MixControl.Level(1), "x", "y")
        collab.record(EditOrigin.USER, MixControl.Pan(1), "L", "C")
        collab.record(EditOrigin.USER, MixControl.Level(1), "b", "c")
        val moves = collab.takeMovesForAgent()
        assertEquals(
            listOf(
                Triple(EditOrigin.USER, MixControl.Level(1), "a" to "c"),
                Triple(EditOrigin.MCP, MixControl.Level(1), "x" to "y"),
                Triple(EditOrigin.USER, MixControl.Pan(1), "L" to "C"),
            ),
            moves.map { Triple(it.origin, it.control, it.before to it.after) },
        )
    }

    @Test
    fun noOpDragIsDropped() {
        collab.record(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-4.0 dB")
        collab.record(EditOrigin.USER, MixControl.Level(3), "-4.0 dB", "-2.0 dB")
        assertTrue(collab.takeMovesForAgent().isEmpty())
    }

    @Test
    fun takeDrainsTheLog() {
        collab.record(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-4.0 dB")
        collab.record(EditOrigin.AGENT, MixControl.Level(4), "-2.0 dB", "-4.0 dB")
        assertEquals(1, collab.takeMovesForAgent().size) // the agent's own moves are not fed back
        assertTrue(collab.takeMovesForAgent().isEmpty())
    }

    @Test
    fun agentTouchedClearsAtTurnEnd() {
        collab.beginTurn()
        collab.record(EditOrigin.AGENT, MixControl.Level(3), "a", "b")
        collab.record(EditOrigin.AGENT, MixControl.Send(5, reverb = false), "a", "b")
        collab.record(EditOrigin.AGENT, MixControl.Section, "A", "B") // no track, no glow
        collab.record(EditOrigin.MCP, MixControl.Level(6), "a", "b")
        assertEquals(setOf(3, 5), collab.agentTouched.value)
        collab.endTurn()
        assertTrue(collab.agentTouched.value.isEmpty())
    }

    @Test
    fun wipeConfirmationIsSingleUse() {
        assertFalse(collab.consumeWipeConfirmation())
        collab.confirmWipe()
        assertTrue(collab.consumeWipeConfirmation())
        assertFalse(collab.consumeWipeConfirmation())
    }

    @Test
    fun clearForNewVibeDropsPinsAndLog() {
        collab.togglePin(1)
        collab.confirmWipe()
        collab.beginTurn()
        collab.record(EditOrigin.USER, MixControl.Level(3), "a", "b")
        collab.record(EditOrigin.AGENT, MixControl.Level(4), "a", "b")
        collab.clearForNewVibe()
        assertTrue(collab.pins.value.isEmpty())
        assertTrue(collab.agentTouched.value.isEmpty())
        assertTrue(collab.takeMovesForAgent().isEmpty())
        assertFalse(collab.consumeWipeConfirmation())
        assertTrue(collab.turnActive.value)
    }

    @Test
    fun aVibeLoadLetsTheTurnBeClaimedAgain() {
        collab.beginTurn("Brighter")
        val first = assertNotNull(collab.claimTurn())
        assertNull(collab.claimTurn())
        collab.clearForNewVibe()
        assertEquals(first, collab.claimTurn())
    }

    @Test
    fun vibeLoadClearsUserMovedThisTurn() {
        collab.setMixerVisible(true)
        collab.beginTurn()
        collab.record(EditOrigin.USER, MixControl.Level(3), "a", "b")
        collab.record(EditOrigin.USER, MixControl.Section, "A", "B")
        assertEquals(Refusal.USER_MOVED, collab.check(EditOrigin.AGENT, MixControl.Level(3)))
        collab.clearForNewVibe()
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Level(3)))
        assertNull(collab.check(EditOrigin.AGENT, MixControl.Section))
        assertTrue(collab.turnActive.value)
    }

    @Test
    fun recordTracksTheLastUserMoveTime() {
        assertNull(collab.lastUserMoveAtMs.value)
        now = 5_000L
        collab.record(EditOrigin.AGENT, MixControl.Level(4), "a", "b")
        assertNull(collab.lastUserMoveAtMs.value)
        collab.record(EditOrigin.USER, MixControl.Level(3), "a", "b")
        assertEquals(5_000L, collab.lastUserMoveAtMs.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun movesAndAgentEditsFlowToTheFeed() = runTest(UnconfinedTestDispatcher()) {
        val moves = mutableListOf<MixMove>()
        val edits = mutableListOf<MixControl>()
        val a = launch(start = CoroutineStart.UNDISPATCHED) { collab.moves.collect { moves += it } }
        val b = launch(start = CoroutineStart.UNDISPATCHED) { collab.agentEdits.collect { edits += it } }
        collab.record(EditOrigin.USER, MixControl.Level(1), "a", "b")
        collab.record(EditOrigin.AGENT, MixControl.Level(2), "a", "b")
        collab.record(EditOrigin.MCP, MixControl.Pan(3), "a", "b")
        assertEquals(listOf(EditOrigin.USER, EditOrigin.AGENT, EditOrigin.MCP), moves.map { it.origin })
        assertEquals(listOf(MixControl.Level(2), MixControl.Pan(3)), edits)
        a.cancel()
        b.cancel()
    }

    @Test
    fun labelsReadLikeTheMixer() {
        val names = listOf("KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX")
        val name: (Int) -> String = { names[it] }
        assertEquals("BASS level", MixControl.Level(3).label(name))
        assertEquals("PAD reverb", MixControl.Send(5, reverb = true).label(name))
        assertEquals("PAD delay", MixControl.Send(5, reverb = false).label(name))
        assertEquals("KICK pan", MixControl.Pan(0).label(name))
        assertEquals("FX mute", MixControl.Mute(7).label(name))
        assertEquals("KEYS solo", MixControl.Solo(4).label(name))
        assertEquals("ENERGY", MixControl.MacroKnob(Macro.ENERGY).label(name))
        assertEquals("section", MixControl.Section.label(name))
        assertEquals("loop", MixControl.Loop.label(name))
        assertEquals("edit scope", MixControl.EditScope.label(name))
    }

    @Test
    fun aStaleConfirmationNeverAuthorisesALaterWipe() {
        collab.confirmWipe()
        collab.beginTurn()
        assertFalse(collab.consumeWipeConfirmation(), "a new turn drops the old confirmation")
        collab.confirmWipe()
        collab.record(EditOrigin.USER, MixControl.ClearAll, "edited", "file")
        assertFalse(collab.consumeWipeConfirmation(), "the user's own reset spends it")
    }

    @Test
    fun aConfirmationNeverOutlivesItsTurn() {
        collab.beginTurn()
        collab.confirmWipe()
        collab.endTurn()
        assertFalse(collab.consumeWipeConfirmation(), "the turn ended without spending it")
    }

    @Test
    fun onlyTheMixCountsTowardAResetsWipe() {
        collab.record(EditOrigin.USER, MixControl.Section, "groove", "drop")
        collab.record(EditOrigin.USER, MixControl.Loop, "off", "on")
        collab.record(EditOrigin.USER, MixControl.EditScope, "whole vibe", "section")
        assertEquals(0, collab.userChangesClearedByReset())
        collab.record(EditOrigin.USER, MixControl.Level(2), "0.0 dB", "-1.0 dB")
        collab.record(EditOrigin.USER, MixControl.Pan(2), "C", "L20")
        collab.record(EditOrigin.USER, MixControl.Send(2, reverb = false), "0.10", "0.30")
        collab.record(EditOrigin.USER, MixControl.Mute(3), "off", "on")
        collab.record(EditOrigin.USER, MixControl.Solo(4), "off", "on")
        collab.record(EditOrigin.USER, MixControl.MacroKnob(Macro.SPACE), "0.50", "0.70")
        assertEquals(6, collab.userChangesClearedByReset())
    }

    @Test
    fun onlyTheUsersRealChangesCount() {
        collab.record(EditOrigin.AGENT, MixControl.Level(1), "0.0 dB", "-3.0 dB")
        collab.record(EditOrigin.MCP, MixControl.Pan(1), "C", "L20")
        collab.record(EditOrigin.USER, MixControl.Level(2), "0.0 dB", "0.0 dB")
        collab.togglePin(5)
        assertTrue(collab.userChanged.value.isEmpty())
        collab.record(EditOrigin.USER, MixControl.Level(2), "0.0 dB", "-1.0 dB")
        collab.record(EditOrigin.USER, MixControl.Mute(3), "off", "on")
        assertEquals(setOf(MixControl.Level(2), MixControl.Mute(3)), collab.userChanged.value)
        collab.clearForNewVibe()
        assertTrue(collab.userChanged.value.isEmpty())
    }

    @Test
    fun resetsClearWhatTheyReset() {
        collab.record(EditOrigin.USER, MixControl.Level(2), "0.0 dB", "-1.0 dB")
        collab.record(EditOrigin.USER, MixControl.Send(4, reverb = true), "0.20", "0.50")
        collab.record(EditOrigin.USER, MixControl.Mute(3), "off", "on")
        collab.record(EditOrigin.USER, MixControl.MacroKnob(Macro.MOOD), "0.50", "0.70")
        collab.record(EditOrigin.USER, MixControl.Mute(4), "off", "on")
        collab.togglePin(4)
        // A reset clears mutes, solos and macro knobs too; only the pinned strip stays, so it is not counted.
        assertEquals(3, collab.userChangesClearedByReset())
        collab.record(EditOrigin.AGENT, MixControl.ClearAll, "edited", "file")
        assertEquals(setOf(MixControl.Send(4, reverb = true), MixControl.Mute(4)), collab.userChanged.value)
        collab.record(EditOrigin.USER, MixControl.Level(2), "0.0 dB", "-2.0 dB")
        collab.record(EditOrigin.USER, MixControl.ClearAll, "edited", "file")
        // The user's own reset leaves the pinned strip's changes too.
        assertEquals(setOf(MixControl.Send(4, reverb = true), MixControl.Mute(4)), collab.userChanged.value)
    }
}
