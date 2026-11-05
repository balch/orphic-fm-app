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

class DrumsToolPortScaleTest {
    private val writes = mutableListOf<PortValue>()
    private val ports = mutableMapOf<PluginControlId, PortValue>(
        BeatsSymbol.BPM.controlId to PortValue.FloatValue(120f),
    )
    private val controller = SynthController().apply {
        setDelegates(
            setter = { id, value -> ports[id] = value; writes += value; true },
            getter = { ports[it] ?: PortValue.FloatValue(0f) },
        )
    }
    private val tool = DrumsTool(controller).tool

    private fun execute(controlId: String, value: Float) = runBlocking {
        tool.execute(DrumsControlArgs(controlId, value))
    }

    @Test
    fun aTempoLandsInBpmAndSaysSo() {
        val result = execute("BPM", 0.5f)
        assertEquals(PortValue.FloatValue(130f), ports[BeatsSymbol.BPM.controlId])
        assertTrue("130" in result.message, result.message)
    }

    @Test
    fun aTempoRampStartsFromTheTempoNow() {
        execute("BEATS_BPM", 1f)
        val first = writes.first().asFloat()
        assertTrue(first in 120f..125f, "the ramp jumped to $first")
        assertEquals(200f, writes.last().asFloat())
    }

    @Test
    fun aLengthLandsInSteps() {
        val length = BeatsSymbol.EUCLIDEAN_0.controlId
        execute("BEATS_EUCLIDEAN_LENGTH_1", 1f)
        assertEquals(PortValue.IntValue(32), ports[length])
        execute("BEATS_EUCLIDEAN_LENGTH_1", 0f)
        assertEquals(PortValue.IntValue(1), ports[length])
    }

    @Test
    fun aModeSwitchesAtHalfwayInOneWrite() {
        val mode = BeatsSymbol.MODE.controlId
        val euclidean = execute("BEATS_MODE", 0.8f)
        assertEquals(PortValue.IntValue(1), ports[mode])
        assertTrue("EUCLIDEAN" in euclidean.message, euclidean.message)
        val drums = execute("BEATS_MODE", 0.2f)
        assertEquals(PortValue.IntValue(0), ports[mode])
        assertTrue("DRUMS" in drums.message, drums.message)
        assertEquals(2, writes.size)
    }

    @Test
    fun otherControlsStayNormalized() {
        execute("DRUM_BD_TONE", 0.25f)
        assertEquals(PortValue.FloatValue(0.25f), ports[DrumSymbol.BD_TONE.controlId])
    }

    @Test
    fun theGenericToolScalesTheSamePorts() {
        assertEquals(PortValue.FloatValue(200f), portValueFor(BeatsSymbol.BPM.controlId, 1f))
        assertEquals(PortValue.IntValue(1), portValueFor(BeatsSymbol.EUCLIDEAN_2.controlId, 0f))
        assertEquals(PortValue.FloatValue(0.5f), portValueFor(BeatsSymbol.SWING.controlId, 0.5f))
    }
}
