package org.balch.orpheus.features.ai.tools

import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrumsToolRunAndTriggerTest {
    private val writes = mutableListOf<Pair<PluginControlId, Boolean>>()
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, value -> writes += id to value.asBoolean(); true },
            getter = { PortValue.BoolValue(false) },
        )
    }
    private val tool = DrumsTool(controller).tool

    private fun execute(controlId: String, value: Float) = runBlocking {
        tool.execute(DrumsControlArgs(controlId, value))
    }

    @Test
    fun aTriggerHitsAndReleasesSoTheNextOneHitsToo() {
        val result = execute("DRUM_SD_TRIGGER", 1f)
        assertTrue(result.success, result.message)
        val id = DrumSymbol.SD_TRIGGER.controlId
        assertEquals(listOf(id to true, id to false), writes)
    }

    @Test
    fun aZeroTriggerDoesNotHit() {
        assertTrue(execute("DRUM_HH_TRIGGER", 0f).success)
        assertEquals(listOf(DrumSymbol.HH_TRIGGER.controlId to false), writes)
    }

    @Test
    fun runStartsAndStopsWithOneWriteEach() {
        val run = BeatsSymbol.RUN.controlId
        execute("BEATS_RUN", 1f)
        execute("BEATS_RUN", 0f)
        assertEquals(listOf(run to true, run to false), writes)
    }
}
