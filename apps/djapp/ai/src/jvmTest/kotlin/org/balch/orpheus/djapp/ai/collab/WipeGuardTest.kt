package org.balch.orpheus.djapp.ai.collab

import org.balch.orpheus.features.ai.AgentErrorLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.djapp.ai.tools.CollabRig
import org.balch.orpheus.djapp.ai.tools.CountingCapture
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.MixResetTool
import org.balch.orpheus.djapp.ai.tools.NoArgs
import org.balch.orpheus.djapp.ai.tools.OneDispatcher
import org.balch.orpheus.djapp.ai.tools.VibeLoadArgs
import org.balch.orpheus.djapp.ai.tools.VibeLoadTool
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.ai.NoOpAiVibeArchive
import org.balch.orpheus.features.ai.TurnBudget
import org.balch.orpheus.features.ai.tools.VibeApplyArgs
import org.balch.orpheus.features.ai.tools.VibeApplyTool
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.VibeCreateEventBus
import org.balch.orpheus.features.pulsar.bench.MixBenchController

@OptIn(ExperimentalCoroutinesApi::class)
class WipeGuardTest {
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }
    private val pulsar = FakePulsar(listOf(vibe("Night Drive"), vibe("Low Tide"), vibe("Red Dust")))

    private class Rig(val controller: MixBenchController, val collab: CollabRig, val events: List<CollabEvent>)

    private suspend fun TestScope.rig(capture: CountingCapture = CountingCapture()): Rig {
        val controller = MixBenchController(pulsar, synth, NoMeterTap, backgroundScope).also { it.start() }
        val collab = CollabRig(pulsar, { controller }, backgroundScope, capture)
        val seen = mutableListOf<CollabEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { collab.events.events.collect { seen += it } }
        runCurrent()
        return Rig(controller, collab, seen)
    }

    private fun applyTool(r: Rig) =
        VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), setOf(r.collab.guard), AgentErrorLog()).tool

    private fun json(name: String) = vibeApplyJson.encodeToString(vibe(name))

    private suspend fun Rig.userEdits() {
        collab.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        collab.mixer.edit(EditOrigin.USER, MixControl.Mute(2)) { toggleMute(2) }
    }

    @Test
    fun reasonCountsOneChange() {
        assertEquals("This would clear the user's live mix (1 change). Ask them first; they confirm with a button.", DjVibeApplyGuard.reason(1))
    }

    @Test
    fun applyWithLiveEditsIsNotRefused() = runTest {
        val r = rig()
        r.userEdits()
        assertNull(r.collab.guard.refusal())
        val applied = applyTool(r).execute(VibeApplyArgs(json("Low Tide")))
        assertTrue(applied.success, applied.message)
        assertNull(applied.refusal)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun theAgentsOwnEditsNeedNoConfirmation() = runTest {
        val r = rig()
        r.collab.mixer.edit(EditOrigin.AGENT, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        withContext(TurnBudget.Exempt) { r.collab.mixer.edit(EditOrigin.MCP, MixControl.Pan(2)) { setPan(2, 0.3f) } }
        assertNull(r.collab.guard.resetRefusal())
        assertTrue(applyTool(r).execute(VibeApplyArgs(json("Low Tide"))).success)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun mcpApplyNeverRefused() = runTest {
        val r = rig()
        r.userEdits()
        r.collab.collab.togglePin(4)
        val applied = withContext(TurnBudget.Exempt) { applyTool(r).execute(VibeApplyArgs(json("Low Tide"))) }
        assertTrue(applied.success, applied.message)
        assertTrue(r.events.isEmpty())
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
    }

    @Test
    fun applyWithoutLiveEditsGoesAhead() = runTest {
        val r = rig()
        assertNull(r.collab.guard.refusal())
        assertTrue(applyTool(r).execute(VibeApplyArgs(json("Low Tide"))).success)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun aPinAloneIsNotALiveEdit() = runTest {
        val r = rig()
        r.collab.collab.togglePin(4)
        assertNull(r.collab.guard.refusal())
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun aSectionJumpAloneNeedsNoConfirmation() = runTest {
        pulsar.section(0)
        val r = rig()
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Section, shown = "chorus") { jumpTo(2) }
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Loop) { setLoop(true) }
        assertEquals(setOf<MixControl>(MixControl.Section, MixControl.Loop), r.collab.collab.userChanged.value, "both moves were the user's")
        val applied = applyTool(r).execute(VibeApplyArgs(json("Low Tide")))
        assertTrue(applied.success, applied.message)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun vibeLoadWithLiveEditsIsNotRefused() = runTest {
        val r = rig()
        r.userEdits()
        val load = VibeLoadTool({ pulsar }, OneDispatcher(UnconfinedTestDispatcher(testScheduler))).tool
        val loaded = load.execute(VibeLoadArgs("Low Tide"))
        assertTrue(loaded.ok, loaded.message)
        assertNull(loaded.refusal)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun fullResetAsksFirstWithLiveEdits() = runTest {
        val r = rig()
        val reset = MixResetTool(r.collab.mixer, r.collab.events, r.collab.guard, r.collab.collab).tool
        // Nothing to lose: no question asked.
        assertTrue(reset.execute(NoArgs()).ok)
        r.userEdits()
        val refused = reset.execute(NoArgs())
        assertFalse(refused.ok)
        assertEquals("WIPE_UNCONFIRMED", refused.refusal)
        // A full reset clears the level and the mute.
        assertEquals(DjVibeApplyGuard.reason(2), refused.message)
        assertEquals(listOf<CollabEvent>(CollabEvent.WipeConfirmRequested(2)), r.events)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
        r.collab.collab.confirmWipe()
        assertTrue(reset.execute(NoArgs()).ok)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[1])
        assertFalse(2 in r.controller.muted.value)
        // A capture would write the edits back into an AI vibe and undo the reset, so a confirmed reset takes none.
        assertEquals(0, r.collab.capture.calls)
        assertEquals(emptySet<MixControl>(), r.collab.collab.userChanged.value)
        // Over MCP a reset never asks.
        r.userEdits()
        assertTrue(withContext(TurnBudget.Exempt) { reset.execute(NoArgs()) }.ok)
    }

    @Test
    fun aResetWithOnlyAUserMuteAsksFirst() = runTest {
        val r = rig()
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Mute(2)) { toggleMute(2) }
        val reset = MixResetTool(r.collab.mixer, r.collab.events, r.collab.guard, r.collab.collab).tool
        val refused = reset.execute(NoArgs())
        assertFalse(refused.ok)
        assertEquals("WIPE_UNCONFIRMED", refused.refusal)
        assertEquals(listOf<CollabEvent>(CollabEvent.WipeConfirmRequested(1)), r.events)
        assertTrue(2 in r.controller.muted.value)
    }

    @Test
    fun aResetLeavesAPinnedStripsMuteAlone() = runTest {
        val r = rig()
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Mute(2)) { toggleMute(2) }
        r.collab.collab.togglePin(2)
        val reset = MixResetTool(r.collab.mixer, r.collab.events, r.collab.guard, r.collab.collab).tool
        // The pinned mute is not the reset's to clear, so there is nothing to ask about.
        val result = reset.execute(NoArgs())
        assertTrue(result.ok, result.message)
        assertTrue(r.events.isEmpty())
        assertTrue(2 in r.controller.muted.value)
    }
}
