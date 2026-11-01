package org.balch.orpheus.djapp.ai.mixer

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.MixCollab
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixMove
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.OneDispatcher
import org.balch.orpheus.djapp.ai.tools.VIBE_LOADING
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.models.Vibe

@OptIn(ExperimentalCoroutinesApi::class)
class AiMixerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val synth = SynthController().apply { setDelegates(setter = { _, _ -> true }, getter = { null }) }

    private class CountingTap : MixBenchTap {
        @Volatile var on = false
        override fun setEnabled(on: Boolean) { this.on = on }
        override fun drain(out: FloatArray) = 0
    }

    private class FakeCapture(private val rows: Int = 2) : MixCaptureService {
        override suspend fun capture() = CaptureOutcome(
            ok = true,
            message = "wrote it",
            file = "/tmp/capture.json",
            rows = List(rows) {
                CaptureRow(Scope.VIBE, null, null, it, CaptureField.VOLUME, null, 1f, 0.5f)
            },
            stale = false,
            aiVibe = true,
            appliedToAiVibe = false,
        )
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class Rig(
        val pulsar: FakePulsar,
        val controller: MixBenchController,
        val vm: AiMixerViewModel,
        val collab: MixCollab,
        val tap: CountingTap,
    )

    private suspend fun TestScope.rig(
        vibe: Vibe = vibe("Night Drive"),
        controllerScope: CoroutineScope = backgroundScope,
        capture: MixCaptureService = FakeCapture(),
    ): Rig {
        val pulsar = FakePulsar(listOf(vibe))
        val tap = CountingTap()
        val controller = MixBenchController(pulsar, synth, tap, controllerScope).also { it.start() }
        val collab = MixCollab()
        val vm = AiMixerViewModel(
            controllerProvider = { controller },
            pulsarFeature = pulsar,
            captureService = capture,
            collabMixer = CollabMixer({ controller }, { pulsar }, collab, backgroundScope),
            collab = collab,
            scope = FeatureCoroutineScope(),
            dispatchers = OneDispatcher(testDispatcher),
        )
        backgroundScope.launch { vm.stateFlow.collect {} }
        runCurrent()
        return Rig(pulsar, controller, vm, collab, tap)
    }

    @Test
    fun levelEditReachesLayers() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.setLevelDb(3, -6f)
        advanceUntilIdle()
        assertEquals(-6f, r.controller.layers.value.vibe.levelDb[3])
        assertEquals(-6f, r.vm.stateFlow.value.tracks[3].levelDb)
    }

    @Test
    fun macroEditMovesKnob() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.setMacro(Macro.ENERGY, 0.7f)
        advanceUntilIdle()
        assertTrue(("energy" to 0.7f) in r.pulsar.macroWrites)
    }

    @Test
    fun metersPollOnlyWhileCollected() = runTest(testDispatcher) {
        val r = rig()
        val job = backgroundScope.launch { r.vm.meters.collect {} }
        advanceTimeBy(200)
        runCurrent()
        assertTrue(r.tap.on)
        assertTrue(r.controller.isMetering)
        job.cancel()
        advanceTimeBy(11_000)
        runCurrent()
        assertFalse(r.controller.isMetering)
        assertFalse(r.tap.on)
    }

    @Test
    fun refusedEditShowsNotice() = runTest(testDispatcher) {
        // A controller on its own scheduler, so its followers never run and the load below stays unfollowed.
        val idle = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
        val r = rig(controllerScope = idle)
        r.pulsar.vibeLoadCount.value++
        r.vm.actions.toggleMute(0)
        advanceUntilIdle()
        assertEquals(VIBE_LOADING, r.vm.stateFlow.value.notice)
        assertTrue(r.controller.muted.value.isEmpty())
        r.vm.actions.dismissNotice()
        advanceUntilIdle()
        assertNull(r.vm.stateFlow.value.notice)
        // Refused again, then the load settles and the next edit lands, which answers the loading notice.
        r.vm.actions.toggleMute(0)
        advanceUntilIdle()
        assertEquals(VIBE_LOADING, r.vm.stateFlow.value.notice)
        r.pulsar.vibeLoadCount.value--
        r.vm.actions.toggleMute(0)
        advanceUntilIdle()
        assertTrue(0 in r.controller.muted.value)
        assertNull(r.vm.stateFlow.value.notice)
    }

    @Test
    fun zeroSendShowsNotice() = runTest(testDispatcher) {
        val base = vibe("Dry")
        val dry = base.copy(
            tracks = base.tracks.mapIndexed { i, t ->
                if (i != 0) t else t.copy(
                    engineEdm = t.engineEdm.copy(reverbSend = 0f),
                    engineSpace = t.engineSpace.copy(reverbSend = 0f),
                )
            },
        )
        val r = rig(vibe = dry)
        val before = r.controller.layers.value
        r.vm.actions.setReverb(0, 0.5f)
        advanceUntilIdle()
        assertNotNull(r.vm.stateFlow.value.notice)
        assertTrue(r.vm.stateFlow.value.notice!!.contains("switch to section scope"))
        assertEquals(before, r.controller.layers.value)
        val zeroSend = r.vm.stateFlow.value.notice
        // An unrelated edit that lands keeps the notice; switching scope, which it asks for, clears it.
        r.vm.actions.setLevelDb(1, -3f)
        advanceUntilIdle()
        assertEquals(-3f, r.vm.stateFlow.value.tracks[1].levelDb)
        assertEquals(zeroSend, r.vm.stateFlow.value.notice)
        r.vm.actions.setScope(Scope.SECTION)
        advanceUntilIdle()
        assertNull(r.vm.stateFlow.value.notice)
    }

    @Test
    fun captureStoresOutcome() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.capture()
        advanceUntilIdle()
        val s = r.vm.stateFlow.value
        assertEquals(2, s.capture?.rows)
        assertEquals("/tmp/capture.json", s.capture?.file)
        assertFalse(s.capturing)
    }

    @Test
    fun historyShowsEditsToggleAndCapturePoint() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.setLevelDb(3, -6f)
        advanceUntilIdle()
        val change = r.vm.stateFlow.value.history.single() as ChangeRow
        assertEquals("you", change.who)
        assertEquals("BASS · level", change.label)
        r.vm.actions.toggleChange(change.id)
        advanceUntilIdle()
        assertFalse((r.vm.stateFlow.value.history.single() as ChangeRow).on)
        assertEquals(0, r.vm.stateFlow.value.onCount)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[3])
        r.vm.actions.capture()
        advanceUntilIdle()
        assertEquals("Captured", (r.vm.stateFlow.value.history.last() as PointRow).label)
    }

    @Test
    fun dismissCaptureClearsIt() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.capture()
        advanceUntilIdle()
        assertNotNull(r.vm.stateFlow.value.capture)
        r.vm.actions.dismissCapture()
        advanceUntilIdle()
        assertNull(r.vm.stateFlow.value.capture)
    }

    @Test
    fun resetClearsMutesButNotPinnedOnes() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.toggleMute(1)
        r.vm.actions.toggleMute(3)
        r.vm.actions.togglePin(3)
        r.vm.actions.setLevelDb(2, -3f)
        advanceUntilIdle()
        assertEquals(-3f, r.vm.stateFlow.value.tracks[2].levelDb)
        r.vm.actions.reset()
        advanceUntilIdle()
        val s = r.vm.stateFlow.value
        assertFalse(s.tracks[1].muted)
        assertTrue(s.tracks[3].muted)
        assertEquals(0f, s.tracks[2].levelDb)
    }

    @Test
    fun pinActionTogglesPin() = runTest(testDispatcher) {
        val r = rig()
        r.vm.actions.togglePin(2)
        advanceUntilIdle()
        assertEquals(setOf(2), r.vm.stateFlow.value.pins)
        assertEquals(setOf(2), r.collab.pins.value)
        r.vm.actions.togglePin(2)
        advanceUntilIdle()
        assertTrue(r.vm.stateFlow.value.pins.isEmpty())
    }

    @Test
    fun agentEditsReachThePanel() = runTest(testDispatcher) {
        val r = rig()
        val seen = mutableListOf<MixControl>()
        backgroundScope.launch { r.vm.agentEdits.collect { seen += it } }
        runCurrent()
        r.collab.record(EditOrigin.AGENT, MixControl.Level(2), "0.0 dB", "-3.0 dB")
        r.collab.record(EditOrigin.USER, MixControl.Level(1), "0.0 dB", "-3.0 dB")
        r.collab.record(EditOrigin.MCP, MixControl.MacroKnob(Macro.MOOD), "0.40", "0.50")
        runCurrent()
        assertEquals(listOf(MixControl.Level(2), MixControl.MacroKnob(Macro.MOOD)), seen)
    }

    @Test
    fun editsAreRecordedAsTheUsers() = runTest(testDispatcher) {
        val r = rig()
        val moves = mutableListOf<MixMove>()
        backgroundScope.launch { r.collab.moves.collect { moves += it } }
        runCurrent()
        r.vm.actions.setLevelDb(3, -6f)
        advanceUntilIdle()
        val move = moves.single()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.Level(3), move.control)
        assertEquals("0.0 dB", move.before)
        assertEquals("-6.0 dB", move.after)
    }

    @Test
    fun resetIsRecordedAsTheUsers() = runTest(testDispatcher) {
        val r = rig()
        val moves = mutableListOf<MixMove>()
        backgroundScope.launch { r.collab.moves.collect { moves += it } }
        runCurrent()
        r.vm.actions.setLevelDb(3, -6f)
        r.vm.actions.reset()
        advanceUntilIdle()
        runCurrent()
        val move = moves.last()
        assertEquals(EditOrigin.USER, move.origin)
        assertEquals(MixControl.ResetToFile, move.control)
        assertEquals("edited" to "file", move.before to move.after)
        assertEquals(0f, r.controller.layers.value.vibe.levelDb[3])
    }
}
