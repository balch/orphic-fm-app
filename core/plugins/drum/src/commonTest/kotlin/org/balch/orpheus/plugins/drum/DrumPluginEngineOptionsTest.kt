package org.balch.orpheus.plugins.drum

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DrumPluginEngineOptionsTest {

    private val audioEngine = object : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning = false
        override val sampleRate = 48000
        override fun getCpuLoad() = 0f
        override fun getCurrentTime() = 0.0
    }

    private val enginePorts = listOf(DrumSymbol.BD_ENGINE, DrumSymbol.SD_ENGINE, DrumSymbol.HH_ENGINE)

    @Test
    fun `drum engine ports do not offer Pulsar-only engines`() {
        val plugin = DrumPlugin(audioEngine)
        for (symbol in enginePorts) {
            val port = plugin.ports.filterIsInstance<ControlPort>().single { it.symbol == symbol.symbol }
            val labels = port.enumLabels.orEmpty()
            assertFalse("Grain Mist" in labels, "${symbol.symbol} must not list Grain Mist")
            assertEquals(labels.size - 1, port.max.toInt(), "${symbol.symbol} max must match its options")
        }
    }

    @Test
    fun `writing a Pulsar-only ordinal to a drum engine port is ignored`() {
        val plugin = DrumPlugin(audioEngine)
        for ((slot, symbol) in enginePorts.withIndex()) {
            val before = plugin.getEngineSlotIndex(slot)
            plugin.setPortValue(symbol.symbol, PortValue.IntValue(OrpheusEngineId.MST.ordinal))
            assertEquals(before, plugin.getEngineSlotIndex(slot), "${symbol.symbol} must keep its engine")
        }
    }
}
