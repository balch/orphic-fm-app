package org.balch.orpheus.core.midi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.MidiNoteEvent
import org.balch.orpheus.core.controller.SynthController
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MidiInputHandlerNoteEventTest {

    @Test
    fun noteOnAndOffArePublished() = runTest(UnconfinedTestDispatcher()) {
        val controller = SynthController()
        val stateHolder = MidiMappingStateHolder()
        val handler = MidiInputHandler(controller, stateHolder)

        val events = mutableListOf<MidiNoteEvent>()
        backgroundScope.launch { controller.onMidiNote.toList(events) }

        handler.onNoteOn(64, 100)
        handler.onNoteOff(64)

        val voice = stateHolder.getVoiceForNote(64)
        assertEquals(
            listOf(
                MidiNoteEvent(64, 100, on = true, voice = voice),
                MidiNoteEvent(64, 0, on = false, voice = voice),
            ),
            events,
        )
    }
}
