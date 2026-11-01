package org.balch.orpheus.core.midi

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.ControlEvent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MidiInputHandlerDxLockTest {
    private val values = mutableMapOf<PluginControlId, PortValue>()
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, v -> values[id] = v; true },
            getter = { values[it] },
        )
    }
    private val stateHolder = MidiMappingStateHolder()
    private val handler = MidiInputHandler(controller, stateHolder)

    private val harmonicsId = VoiceSymbol.duoHarmonics(2).controlId
    private val engineId = VoiceSymbol.duoEngine(2).controlId

    private fun cc(controlId: String, value: Int) {
        stateHolder.updateState { it.assignCCToControl(21, controlId) }
        handler.onControlChange(21, value)
    }

    @Test
    fun duoHarmonicsCcIgnoredOnDx() {
        values[engineId] = PortValue.IntValue(OrpheusEngineId.DX2.id)
        values[harmonicsId] = PortValue.FloatValue(0.25f)
        cc(harmonicsId.key, 100)
        assertEquals(PortValue.FloatValue(0.25f), values[harmonicsId])
    }

    @Test
    fun duoHarmonicsCcAppliesOnVa() {
        values[engineId] = PortValue.IntValue(OrpheusEngineId.VA.id)
        values[harmonicsId] = PortValue.FloatValue(0.25f)
        cc(harmonicsId.key, 127)
        assertEquals(1f, values[harmonicsId]?.asFloat())
    }

    @Test
    fun drumModCcIgnoredOnDxOrdinalAndAppliesOtherwise() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<ControlEvent>()
        backgroundScope.launch { controller.onControlChange.toList(events) }

        values[DrumSymbol.BD_ENGINE.controlId] = PortValue.IntValue(OrpheusEngineId.DX.ordinal)
        cc("drums_bd_mod", 100)
        assertEquals(emptyList(), events.filter { it.controlId == "drums_bd_mod" })

        values[DrumSymbol.BD_ENGINE.controlId] = PortValue.IntValue(OrpheusEngineId.VA.ordinal)
        cc("drums_bd_mod", 100)
        assertEquals(1, events.count { it.controlId == "drums_bd_mod" })
    }
}
