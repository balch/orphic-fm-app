package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lick Lab's per-track live note input: declared so the DJ app can write it, never saved in a preset. */
class PulsarLiveInputPortTest {

    private val defaults = mapOf(
        "active" to 0f, "note" to 60f, "gate" to 0f,
        "strike" to 0f, "velocity" to 0.8f, "glide" to 0f,
    )

    @Test
    fun `each track has all six live ports named track_N_live_x and excluded from presets`() {
        val plugin = PulsarPlugin()
        for (t in 0..7) for ((name, default) in defaults) {
            val key = "track_${t}_live_$name"
            assertTrue(PulsarSymbol.entries.any { it.symbol == key }, key)
            val port = plugin.ports.single { it.symbol == key }
            assertTrue(port is ControlPort && port.isInput, key)
            assertTrue(port.excludeFromPresets, "$key must never be saved in a preset")
            assertEquals(default, port.default, key)
            assertEquals(default, plugin.getPortValue(key)?.asFloat(), key)
        }
    }

    @Test
    fun `writes are stored per track and a strike counter is not clamped`() {
        val plugin = PulsarPlugin()
        assertTrue(plugin.setPortValue("track_3_live_note", PortValue.FloatValue(72f)))
        assertTrue(plugin.setPortValue("track_3_live_strike", PortValue.IntValue(5000)))
        assertTrue(plugin.setPortValue("track_3_live_gate", PortValue.IntValue(1)))
        assertEquals(72f, plugin.getPortValue("track_3_live_note")?.asFloat())
        assertEquals(5000f, plugin.getPortValue("track_3_live_strike")?.asFloat())
        assertEquals(1f, plugin.getPortValue("track_3_live_gate")?.asFloat())
        assertEquals(60f, plugin.getPortValue("track_2_live_note")?.asFloat())
    }
}
