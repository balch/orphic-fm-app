package org.balch.orpheus.features.record

import org.balch.orpheus.core.controller.MidiNoteEvent
import org.balch.orpheus.features.record.telemetry.Control
import org.balch.orpheus.features.record.telemetry.GateOff
import org.balch.orpheus.features.record.telemetry.GateOn
import org.balch.orpheus.features.record.telemetry.MidiNoteOff
import org.balch.orpheus.features.record.telemetry.MidiNoteOn
import org.balch.orpheus.features.record.telemetry.TakeEventRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TakeEventRecorderTest {

    private var clockMs = 0L
    private val recorder = TakeEventRecorder { clockMs }
    private val tuneKey = "org.balch.orpheus.plugins.voice:tune_1"

    @Test
    fun ignoresEventsBeforeStart() {
        recorder.gateOn(1, 60f)
        recorder.start()
        assertTrue(recorder.stop().isEmpty())
    }

    @Test
    fun ignoresEventsAfterStop() {
        recorder.start()
        recorder.stop()
        assertFalse(recorder.isActive)
        recorder.gateOn(1, 60f)
        recorder.start()
        assertTrue(recorder.stop().isEmpty())
    }

    @Test
    fun timesAreSecondsFromStart() {
        clockMs = 1000
        recorder.start()
        clockMs = 1840
        recorder.gateOn(2, 60f)
        clockMs = 2000
        recorder.gateOff(2)

        val events = recorder.stop()
        assertEquals(GateOn(0.84f, 2, 60f), events[0])
        assertEquals(GateOff(1.0f, 2), events[1])
    }

    @Test
    fun throttlesControlsPerKey() {
        recorder.start()
        repeat(10) { i ->
            recorder.control(tuneKey, i / 10f)
            clockMs += 10
        }

        val controls = recorder.stop().filterIsInstance<Control>().filter { it.key == tuneKey }
        assertTrue(controls.size <= 3, "got ${controls.size}")
        assertEquals(0.0f, controls.first().value)
        assertEquals(0.9f, controls.last().value)
    }

    @Test
    fun throttleIsIndependentPerKey() {
        recorder.start()
        recorder.control(tuneKey, 0.1f)
        recorder.control("org.balch.orpheus.plugins.voice:tune_2", 0.2f)

        assertEquals(2, recorder.stop().filterIsInstance<Control>().size)
    }

    @Test
    fun eventAfterWindowIsKeptAndDropsHeldValue() {
        recorder.start()
        recorder.control(tuneKey, 0.1f)
        clockMs = 10
        recorder.control(tuneKey, 0.2f)
        clockMs = 60
        recorder.control(tuneKey, 0.3f)

        val values = recorder.stop().filterIsInstance<Control>().map { it.value }
        assertEquals(listOf(0.1f, 0.3f), values)
    }

    @Test
    fun dropsUnrelatedUris() {
        recorder.start()
        recorder.control("org.balch.orpheus.plugins.pulsar:foo", 1f)
        assertTrue(recorder.stop().isEmpty())
    }

    @Test
    fun openGateAtStopIsKept() {
        recorder.start()
        recorder.gateOn(1, 60f)

        val events = recorder.stop()
        assertEquals(listOf(GateOn(0f, 1, 60f)), events)
    }

    @Test
    fun midiNotesRecorded() {
        clockMs = 500
        recorder.start()
        clockMs = 1500
        recorder.midiNote(MidiNoteEvent(64, 100, true, 1))

        assertEquals(listOf(MidiNoteOn(1.0f, 64, 100, 1)), recorder.stop())
    }

    @Test
    fun midiNoteOffRecorded() {
        clockMs = 500
        recorder.start()
        clockMs = 900
        recorder.midiNote(MidiNoteEvent(64, 0, on = false, voice = 1))

        assertEquals(listOf(MidiNoteOff(0.4f, 64, 1)), recorder.stop())
    }
}
