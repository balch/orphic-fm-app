package org.balch.orpheus.features.record

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.features.record.telemetry.GateOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecordSessionTest {

    private class Rig(scope: TestScope, beforeSession: (SynthController) -> Unit = {}) {
        val capture = FakeOutputCapture()
        val store = FakeTakeStore()
        val presets = FakePresetSnapshotter()
        val controller = SynthController()
        val values = mutableMapOf<PluginControlId, PortValue>()
        var getterFails = false
        private val base: Instant =
            LocalDateTime(2026, 10, 17, 13, 24, 31).toInstant(TimeZone.currentSystemDefault())
        private val clock = object : Clock {
            override fun now() = base + scope.testScheduler.currentTime.milliseconds
        }
        val session: RecordSession

        init {
            controller.setDelegates(
                setter = { _, _ -> true },
                getter = { if (getterFails) error("getter failed") else values[it] },
            )
            beforeSession(controller)
            session = RecordSession(
                capture, store, presets, controller, clock, scope.backgroundScope,
                StandardTestDispatcher(scope.testScheduler),
            )
        }
    }

    @Test
    fun startSavesPresetNamedAfterTake() = runTest {
        val rig = Rig(this)
        rig.session.start()

        assertEquals("take-2026-10-17-132431", rig.store.openedId)
        assertEquals(listOf("rec-take-2026-10-17-132431"), rig.presets.saved.map { it.name })
        assertEquals(RecordState.Recording("take-2026-10-17-132431", 0f), rig.session.state.value)
        assertTrue(rig.capture.running)
        rig.session.stop()
    }

    @Test
    fun audioChunksReachTheWriter() = runTest {
        val rig = Rig(this)
        repeat(3) { rig.capture.chunks += 100 }
        rig.session.start()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(300, rig.store.writer.frames)

        rig.session.stop()
        assertEquals(300 / 48_000f, rig.store.writer.telemetry!!.durationSec)
        assertEquals(RecordState.Idle, rig.session.state.value)
    }

    @Test
    fun liveGateJustBeforeStartIsNotRecorded() = runTest {
        val rig = Rig(this)
        rig.controller.emitPulseStart(1)
        rig.session.start()
        runCurrent()
        rig.session.stop()

        assertTrue(rig.store.writer.telemetry!!.events.filterIsInstance<GateOn>().isEmpty())
    }

    @Test
    fun replayedGateFromBeforeSessionExistedIsNotRecorded() = runTest {
        val rig = Rig(this, beforeSession = { it.emitPulseStart(1) })
        rig.session.start()
        runCurrent()
        rig.session.stop()

        assertTrue(rig.store.writer.telemetry!!.events.filterIsInstance<GateOn>().isEmpty())
    }

    @Test
    fun throwingHandlerDoesNotEndCapture() = runTest {
        val rig = Rig(this)
        rig.session.start()
        rig.getterFails = true
        rig.controller.emitPulseStart(1)
        runCurrent()
        rig.getterFails = false
        rig.controller.emitPulseStart(2)
        runCurrent()
        rig.session.stop()

        val gates = rig.store.writer.telemetry!!.events.filterIsInstance<GateOn>()
        assertEquals(listOf(2), gates.map { it.voice })
    }

    @Test
    fun gateDuringTakeCarriesPitch() = runTest {
        val rig = Rig(this)
        rig.values[VoiceSymbol.tune(1).controlId] = PortValue.FloatValue(0.72f)
        rig.values[VoiceSymbol.quadPitch(0).controlId] = PortValue.FloatValue(0.5f)
        rig.session.start()
        runCurrent()
        rig.controller.emitPulseStart(1)
        runCurrent()
        rig.session.stop()

        val gate = rig.store.writer.telemetry!!.events.filterIsInstance<GateOn>().single()
        assertEquals(1, gate.voice)
        assertEquals(55.56f, gate.midiNote, 0.01f)
    }

    @Test
    fun droppedFramesAreRelativeToStart() = runTest {
        val rig = Rig(this)
        rig.capture.dropped = 50
        rig.session.start()
        rig.capture.dropped = 70
        rig.session.stop()

        assertEquals(20L, rig.store.writer.telemetry!!.droppedFrames)
    }

    @Test
    fun stopsItselfAtFiveMinutesOfAudio() = runTest {
        val rig = Rig(this)
        val finished = mutableListOf<String>()
        backgroundScope.launch { rig.session.takesChanged.collect { finished += it } }
        runCurrent()
        rig.capture.queueFrames(RecordSession.MAX_SECONDS.toLong() * 48_000)
        rig.session.start()

        advanceTimeBy(RecordSession.DRAIN_INTERVAL_MS + 1)
        runCurrent()

        assertEquals(RecordState.Idle, rig.session.state.value)
        assertEquals(listOf("take-2026-10-17-132431"), finished)
        assertEquals(RecordSession.MAX_SECONDS.toFloat(), rig.store.writer.telemetry!!.durationSec)
    }

    @Test
    fun wallClockAloneDoesNotEndTake() = runTest {
        val rig = Rig(this)
        rig.session.start()
        advanceTimeBy(RecordSession.MAX_SECONDS * 1000L + 1000)
        runCurrent()

        assertTrue(rig.session.state.value is RecordState.Recording)
        rig.session.stop()
    }

    @Test
    fun openFailureSavesNothingAndStartsNothing() = runTest {
        val rig = Rig(this)
        rig.store.throwOnOpen = true
        rig.session.start()

        assertEquals(RecordState.Idle, rig.session.state.value)
        assertEquals(0, rig.capture.startCount)
        assertTrue(rig.presets.saved.isEmpty())
        assertTrue(!rig.store.writer.aborted)
    }

    @Test
    fun presetSaveFailureAbortsOpenedTake() = runTest {
        val rig = Rig(this)
        rig.presets.throwOnSave = true
        rig.session.start()

        assertTrue(rig.store.writer.aborted)
        assertEquals(0, rig.capture.startCount)
        assertEquals(RecordState.Idle, rig.session.state.value)
    }

    @Test
    fun failureAbortsTake() = runTest {
        val rig = Rig(this)
        rig.store.writer.throwOnWrite = true
        rig.capture.chunks += 100
        rig.session.start()
        advanceTimeBy(100)
        runCurrent()

        assertTrue(rig.store.writer.aborted)
        assertEquals(RecordState.Idle, rig.session.state.value)
        assertEquals(null, rig.store.writer.telemetry)
        assertTrue(!rig.capture.running)
    }

    @Test
    fun endSnapshotFailureStillFinishesTake() = runTest {
        val rig = Rig(this)
        val finished = mutableListOf<String>()
        backgroundScope.launch { rig.session.takesChanged.collect { finished += it } }
        runCurrent()
        rig.capture.chunks += 100
        rig.session.start()
        advanceTimeBy(100)
        runCurrent()
        rig.presets.throwOnCapture = IllegalStateException("concurrent modification")
        rig.session.stop()
        runCurrent()

        val telemetry = rig.store.writer.telemetry!!
        assertTrue(!rig.store.writer.aborted)
        assertEquals(telemetry.start, telemetry.end)
        assertEquals(100 / 48_000f, telemetry.durationSec)
        assertEquals(RecordState.Idle, rig.session.state.value)
        assertEquals(listOf("take-2026-10-17-132431"), finished)
    }

    @Test
    fun sameSecondRestartGetsSuffixedId() = runTest {
        val rig = Rig(this)
        rig.store.existingIds += "take-2026-10-17-132431"
        rig.session.start()

        assertEquals("take-2026-10-17-132431-2", rig.store.openedId)
        assertEquals(listOf("rec-take-2026-10-17-132431-2"), rig.presets.saved.map { it.name })
        assertEquals(RecordState.Recording("take-2026-10-17-132431-2", 0f), rig.session.state.value)
        rig.session.stop()
    }

    @Test
    fun suffixSkipsAllTakenIdsThenGivesUp() = runTest {
        val rig = Rig(this)
        val base = "take-2026-10-17-132431"
        rig.store.existingIds += base
        (2..9).forEach { rig.store.existingIds += "$base-$it" }
        rig.session.start()

        assertEquals(RecordState.Idle, rig.session.state.value)
        assertEquals(0, rig.capture.startCount)
        assertEquals(9, rig.store.openAttempts.size)
    }
}
