package org.balch.orpheus.core.midi

import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The ordinals are CowbellBody's: 0 STRING, 1 SITAR, 2 METAL, 3 SYNTH. */
class MidiInputHandlerCowbellBodyTest {
    private val values = mutableMapOf<PluginControlId, PortValue>()
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, v -> values[id] = v; true },
            getter = { values[it] },
        )
    }
    private val stateHolder = MidiMappingStateHolder()
    private val handler = MidiInputHandler(controller, stateHolder)
    private val bodyId = CowbellSymbol.BODY.controlId

    private fun cc(value: Int): PortValue? {
        handler.onControlChange(21, value)
        return values[bodyId]
    }

    private fun learnCc() = stateHolder.updateState { it.assignCCToControl(21, bodyId.key) }

    @Test
    fun aLearnedCcReachesStringMetalAndSynth() {
        learnCc()
        assertEquals(PortValue.IntValue(0), cc(0))
        assertEquals(PortValue.IntValue(2), cc(64))
        assertEquals(PortValue.IntValue(3), cc(127))
    }

    @Test
    fun aSweepPassesEveryBodyInOrder() {
        learnCc()
        val seen = (0..127).map { ordinalOf(cc(it)) }
        assertEquals(listOf(0, 1, 2, 3), seen.distinct())
    }

    @Test
    fun aJumpBackToTheTopIsStillSynth() {
        // A jump from low to high on a plain control toggles like a button; a body is not one.
        learnCc()
        repeat(3) {
            assertEquals(PortValue.IntValue(0), cc(0))
            assertEquals(PortValue.IntValue(3), cc(127))
        }
    }

    @Test
    fun aMappedNoteCyclesAllFourBodies() {
        stateHolder.updateState { it.assignNoteToControl(60, bodyId.key) }
        val seen = (0 until 5).map {
            handler.onNoteOn(60, 100)
            handler.onNoteOff(60)
            ordinalOf(values[bodyId])
        }
        assertEquals(listOf(1, 2, 3, 0, 1), seen)
    }

    private fun ordinalOf(value: PortValue?): Int = assertIs<PortValue.IntValue>(value).value
}
