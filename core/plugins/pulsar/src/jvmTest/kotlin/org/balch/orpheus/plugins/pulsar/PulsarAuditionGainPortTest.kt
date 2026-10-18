package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The debug TRACKS view's per-track gain: declared so the boot sync replays it, kept out of presets. */
class PulsarAuditionGainPortTest {

    private fun symbol(t: Int) = PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + t]

    @Test
    fun `each track has an audition gain port named track_N_audition_gain`() {
        val plugin = PulsarPlugin()
        for (t in 0..7) {
            assertEquals("track_${t}_audition_gain", symbol(t).symbol)
            val port = plugin.ports.single { it.symbol == symbol(t).symbol }
            assertTrue(port is ControlPort && port.isInput)
            assertTrue(port.excludeFromPresets, "a preset must never save a muted or soloed track")
            assertEquals(1f, port.default)
        }
    }

    @Test
    fun `writes are stored and forwarded per track`() {
        val plugin = PulsarPlugin()
        for (t in 0..7) assertEquals(1f, plugin.getPortValue(symbol(t).symbol)?.asFloat())
        assertTrue(plugin.setPortValue(symbol(5).symbol, FloatValue(0f)))
        assertTrue(plugin.setPortValue(symbol(2).symbol, FloatValue(1.5f)))
        assertEquals(0f, plugin.getPortValue(symbol(5).symbol)?.asFloat())
        assertEquals(1.5f, plugin.getPortValue(symbol(2).symbol)?.asFloat())
        assertEquals(1f, plugin.getPortValue(symbol(4).symbol)?.asFloat())
    }
}
