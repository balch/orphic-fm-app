package org.balch.orpheus.core.midi

import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.ports.PortRegistry
import org.balch.orpheus.plugins.beats.BeatsPlugin
import org.balch.orpheus.plugins.drum.DrumPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MidiRunAndTriggerPortTest {
    private val hits = mutableListOf<Int>()
    private val audioEngine = object : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning = false
        override val sampleRate = 48000
        override fun getCpuLoad() = 0f
        override fun getCurrentTime() = 0.0
        override fun triggerDrum(type: Int, accent: Float) {
            hits += type
        }
    }
    private val registry = PortRegistry(setOf(BeatsPlugin(), DrumPlugin(audioEngine)))
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, v -> registry.setPortValue(id.key, v) },
            getter = { registry.getPortValue(it.key) },
        )
    }
    private val stateHolder = MidiMappingStateHolder()
    private val handler = MidiInputHandler(controller, stateHolder, registry)

    private fun learnCc(port: PortSymbol) =
        stateHolder.updateState { it.assignCCToControl(CC, port.controlId.key) }

    private fun learnNote(port: PortSymbol) =
        stateHolder.updateState { it.assignNoteToControl(NOTE, port.controlId.key) }

    private fun isOn(port: PortSymbol) = registry.getPortValue(port.controlId.key)!!.asBoolean()

    @Test
    fun everyNotePressHitsTheDrum() {
        learnNote(DrumSymbol.SD_TRIGGER)
        repeat(3) {
            handler.onNoteOn(NOTE, 100)
            handler.onNoteOff(NOTE)
        }
        assertEquals(listOf(1, 1, 1), hits)
    }

    @Test
    fun aNoteHoldsTheTriggerUntilItsRelease() {
        learnNote(DrumSymbol.HH_TRIGGER)
        handler.onNoteOn(NOTE, 100)
        assertTrue(isOn(DrumSymbol.HH_TRIGGER))
        handler.onNoteOff(NOTE)
        assertFalse(isOn(DrumSymbol.HH_TRIGGER))
    }

    @Test
    fun aNoteOnWithNoVelocityReleases() {
        learnNote(DrumSymbol.BD_TRIGGER)
        handler.onNoteOn(NOTE, 100)
        handler.onNoteOn(NOTE, 0)
        handler.onNoteOn(NOTE, 100)
        assertEquals(listOf(0, 0), hits)
    }

    @Test
    fun aCcButtonHitsOnEveryPress() {
        learnCc(DrumSymbol.BD_TRIGGER)
        repeat(3) {
            handler.onControlChange(CC, 127)
            handler.onControlChange(CC, 0)
        }
        assertEquals(listOf(0, 0, 0), hits)
    }

    @Test
    fun aCcSweepHitsOnceOnTheWayUp() {
        learnCc(DrumSymbol.BD_TRIGGER)
        for (value in listOf(20, 70, 90, 110, 127)) handler.onControlChange(CC, value)
        assertEquals(listOf(0), hits)
    }

    @Test
    fun savedTriggerIdsHitTheirDrums() {
        val saved = listOf("drums_bd_trig" to 0, "drums_sd_trig" to 1, "drums_hh_trig" to 2)
        for ((savedId, slot) in saved) {
            hits.clear()
            stateHolder.restoreSaved(MidiMappingState(noteControlMappings = mapOf(NOTE to savedId)))
            handler.onNoteOn(NOTE, 100)
            handler.onNoteOff(NOTE)
            assertEquals(listOf(slot), hits, savedId)
        }
    }

    @Test
    fun aNoteTogglesRun() {
        learnNote(BeatsSymbol.RUN)
        handler.onNoteOn(NOTE, 100)
        assertTrue(isOn(BeatsSymbol.RUN))
        handler.onNoteOn(NOTE, 100)
        assertFalse(isOn(BeatsSymbol.RUN))
    }

    @Test
    fun aCcButtonTogglesRun() {
        learnCc(BeatsSymbol.RUN)
        handler.onControlChange(CC, 127)
        handler.onControlChange(CC, 0)
        assertTrue(isOn(BeatsSymbol.RUN))
        handler.onControlChange(CC, 127)
        handler.onControlChange(CC, 0)
        assertFalse(isOn(BeatsSymbol.RUN))
    }

    @Test
    fun aSavedRunIdReachesThePort() {
        stateHolder.restoreSaved(MidiMappingState(noteControlMappings = mapOf(NOTE to "beats_run")))
        handler.onNoteOn(NOTE, 100)
        assertTrue(isOn(BeatsSymbol.RUN))
    }

    @Test
    fun aFirstPressStopsRunThatThePanelStarted() {
        controller.setPluginControl(BeatsSymbol.RUN.controlId, PortValue.BoolValue(true))
        learnNote(BeatsSymbol.RUN)
        handler.onNoteOn(NOTE, 100)
        assertFalse(isOn(BeatsSymbol.RUN))
    }

    @Test
    fun aFirstCcPressStopsRunThatThePanelStarted() {
        controller.setPluginControl(BeatsSymbol.RUN.controlId, PortValue.BoolValue(true))
        learnCc(BeatsSymbol.RUN)
        handler.onControlChange(CC, 127)
        assertFalse(isOn(BeatsSymbol.RUN))
    }

    @Test
    fun presetsLeaveRunAndTriggersAlone() {
        val ports = listOf(BeatsSymbol.RUN, DrumSymbol.BD_TRIGGER, DrumSymbol.SD_TRIGGER, DrumSymbol.HH_TRIGGER)
        controller.setPluginControl(BeatsSymbol.RUN.controlId, PortValue.BoolValue(true))

        val captured = registry.captureState()
        for (port in ports) assertFalse(port.controlId.key in captured, port.symbol)

        registry.restoreState(ports.associate { it.controlId.key to PortValue.BoolValue(true) })
        assertTrue(isOn(BeatsSymbol.RUN))
        assertEquals(emptyList(), hits)
    }

    private companion object {
        const val CC = 21
        const val NOTE = 72
    }
}
