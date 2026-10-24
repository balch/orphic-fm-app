package org.balch.orpheus.djapp.ai.collab

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
import org.balch.orpheus.features.ai.tools.VibeApplyGuard
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
        VibeApplyTool({ pulsar }, VibeCreateEventBus(), NoOpAiVibeArchive(), TurnBudget(), setOf(r.collab.guard)).tool

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
    fun applyRefusedWithLiveEditsUntilConfirmed() = runTest {
        val r = rig()
        r.userEdits()
        val apply = applyTool(r)
        val refused = apply.execute(VibeApplyArgs(json("Low Tide")))
        assertFalse(refused.success)
        assertEquals("WIPE_UNCONFIRMED", refused.refusal)
        assertEquals("This would clear the user's live mix (2 changes). Ask them first; they confirm with a button.", refused.message)
        assertEquals(listOf<CollabEvent>(CollabEvent.WipeConfirmRequested(2)), r.events)
        assertEquals("Night Drive", pulsar.vibeFlow.value.name)
        assertEquals(-4f, r.controller.layers.value.vibe.levelDb[1])
        assertEquals(0, r.collab.capture.calls)

        r.collab.collab.confirmWipe()
        val applied = apply.execute(VibeApplyArgs(json("Low Tide")))
        assertTrue(applied.success, applied.message)
        assertNull(applied.refusal)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        // The confirmed wipe keeps the user's mix as a capture first.
        assertEquals(1, r.collab.capture.calls)
    }

    @Test
    fun refusedAppliesLeaveTheBudgetForTheConfirmedOne() = runTest {
        val r = rig()
        r.userEdits()
        val apply = applyTool(r)
        repeat(TurnBudget.MAX_APPLIES) {
            val refused = apply.execute(VibeApplyArgs(json("Low Tide")))
            assertEquals("WIPE_UNCONFIRMED", refused.refusal)
            assertFalse(refused.budgetSpent)
        }
        r.collab.collab.confirmWipe()
        val applied = apply.execute(VibeApplyArgs(json("Low Tide")))
        assertTrue(applied.success, applied.message)
    }

    @Test
    fun theAgentsOwnEditsNeedNoConfirmation() = runTest {
        val r = rig()
        r.collab.mixer.edit(EditOrigin.AGENT, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        withContext(TurnBudget.Exempt) { r.collab.mixer.edit(EditOrigin.MCP, MixControl.Pan(2)) { setPan(2, 0.3f) } }
        assertNull(r.collab.guard.wipeRefusal())
        assertNull(r.collab.guard.resetRefusal())
        assertTrue(applyTool(r).execute(VibeApplyArgs(json("Low Tide"))).success)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.collab.capture.calls)
    }

    @Test
    fun aFailedCaptureNeverBlocksTheConfirmedWipe() = runTest {
        val r = rig(CountingCapture(fail = true))
        r.userEdits()
        r.collab.collab.confirmWipe()
        assertTrue(applyTool(r).execute(VibeApplyArgs(json("Low Tide"))).success)
        assertEquals(1, r.collab.capture.calls)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
    }

    @Test
    fun confirmationCoversOneApply() = runTest {
        val r = rig()
        r.userEdits()
        r.collab.collab.confirmWipe()
        assertNull(r.collab.guard.refusal())
        assertEquals(1, r.collab.capture.calls)
        assertEquals(VibeApplyGuard.REFUSAL, Refusal.WIPE_UNCONFIRMED.name)
        assertEquals("This would clear the user's live mix (2 changes). Ask them first; they confirm with a button.", r.collab.guard.refusal())

        // After a confirmed apply the new vibe's mix is the user's to edit again, and the next wipe asks again.
        r.collab.collab.confirmWipe()
        val apply = applyTool(r)
        assertTrue(apply.execute(VibeApplyArgs(json("Low Tide"))).success)
        runCurrent()
        r.userEdits()
        assertEquals("WIPE_UNCONFIRMED", apply.execute(VibeApplyArgs(json("Red Dust"))).refusal)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
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
    fun aLevelMoveAfterASectionJumpCountsOneChange() = runTest {
        pulsar.section(0)
        val r = rig()
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Section, shown = "chorus") { jumpTo(2) }
        r.collab.mixer.edit(EditOrigin.USER, MixControl.Level(1)) { setHeardLevelDb(1, -4f) }
        val refused = applyTool(r).execute(VibeApplyArgs(json("Low Tide")))
        assertEquals("WIPE_UNCONFIRMED", refused.refusal)
        assertEquals(DjVibeApplyGuard.reason(1), refused.message)
        assertEquals(listOf<CollabEvent>(CollabEvent.WipeConfirmRequested(1)), r.events)
    }

    @Test
    fun vibeLoadAsksFirst() = runTest {
        val r = rig()
        r.userEdits()
        val load = VibeLoadTool({ pulsar }, OneDispatcher(UnconfinedTestDispatcher(testScheduler)), r.collab.guard).tool
        val refused = load.execute(VibeLoadArgs("Low Tide"))
        assertFalse(refused.ok)
        assertEquals("WIPE_UNCONFIRMED", refused.refusal)
        assertEquals("Night Drive", pulsar.vibeFlow.value.name)
        r.collab.collab.confirmWipe()
        assertTrue(load.execute(VibeLoadArgs("Low Tide")).ok)
        assertEquals("Low Tide", pulsar.vibeFlow.value.name)
        assertEquals(1, r.collab.capture.calls)
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
