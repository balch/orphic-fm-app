package org.balch.orpheus.plugins.cowbell

import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.COWBELL_URI
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CowbellPluginTest {

    private val plugin = CowbellPlugin()
    private val controls = plugin.ports.filterIsInstance<ControlPort>().associateBy { it.symbol }

    private fun port(symbol: CowbellSymbol) = controls.getValue(symbol.symbol)

    @Test
    fun `every symbol is a port`() {
        assertEquals(CowbellSymbol.entries.map { it.symbol }.toSet(), controls.keys)
        assertEquals(COWBELL_URI, plugin.info.uri)
    }

    @Test
    fun `exactly seven ports are excluded from presets`() {
        val excluded = controls.values.filter { it.excludeFromPresets }.map { it.symbol }.toSet()
        assertEquals(
            setOf("strike", "gate", "bend", "structure_offset", "position_offset", "live", "more"),
            excluded,
        )
    }

    @Test
    fun `defaults match the engine`() {
        assertEquals(0.7f, port(CowbellSymbol.LEVEL).default)
        assertEquals(0.48f, port(CowbellSymbol.RING).default)
        assertEquals(2f, port(CowbellSymbol.BODY).default)
        assertEquals(0f, port(CowbellSymbol.BODY).min)
        assertEquals(3f, port(CowbellSymbol.BODY).max)
        assertEquals(0f, port(CowbellSymbol.TONE).default)
        assertEquals(0.85f, port(CowbellSymbol.INTENSITY).default)
        assertEquals(0f, port(CowbellSymbol.OCTAVE).default)
        assertEquals(-2f, port(CowbellSymbol.OCTAVE).min)
        assertEquals(2f, port(CowbellSymbol.OCTAVE).max)
        assertEquals(1f, port(CowbellSymbol.GRID).default)
        assertEquals(0.7f, port(CowbellSymbol.GRID_STRENGTH).default)
        assertEquals(0f, port(CowbellSymbol.MODE).default)
        assertEquals(2f, port(CowbellSymbol.MODE).max)
        assertEquals(0f, port(CowbellSymbol.FILL).default)
        assertEquals(3f, port(CowbellSymbol.FILL).max)
    }

    @Test
    fun `live port defaults off and is excluded from presets`() {
        val port = port(CowbellSymbol.LIVE)
        assertEquals("live", port.symbol)
        assertTrue(port.excludeFromPresets)
        assertEquals(PortValue.BoolValue(false), plugin.getPortValue("live"))
        plugin.setPortValue("live", PortValue.BoolValue(true))
        assertEquals(PortValue.BoolValue(true), plugin.getPortValue("live"))
    }

    @Test
    fun `more port defaults to zero and is excluded from presets`() {
        val port = port(CowbellSymbol.MORE)
        assertEquals("more", port.symbol)
        assertTrue(port.excludeFromPresets)
        assertEquals(0f, port.default)
        assertEquals(0f, port.min)
        assertEquals(1f, port.max)
        plugin.setPortValue("more", PortValue.FloatValue(0.4f))
        assertEquals(PortValue.FloatValue(0.4f), plugin.getPortValue("more"))
        plugin.setPortValue("more", PortValue.FloatValue(5f))
        assertEquals(PortValue.FloatValue(1f), plugin.getPortValue("more"))
    }

    @Test
    fun `the retired metal and engine ports are gone`() {
        assertFalse(controls.containsKey("metal"))
        assertFalse(controls.containsKey("engine"))
    }

    @Test
    fun `enum sizes match the port ranges`() {
        assertEquals(port(CowbellSymbol.MODE).max.toInt() + 1, CowbellMode.entries.size)
        assertEquals(port(CowbellSymbol.FILL).max.toInt() + 1, CowbellFill.entries.size)
        assertEquals(port(CowbellSymbol.BODY).max.toInt() + 1, CowbellBody.entries.size)
    }

    @Test
    fun `option labels follow the enums`() {
        assertEquals(listOf("Tap", "Shake", "Hold"), port(CowbellSymbol.MODE).enumLabels)
        assertEquals(listOf("Marble", "Beads", "Seeds", "Jingles"), port(CowbellSymbol.FILL).enumLabels)
        assertEquals(listOf("String", "Sitar", "Metal", "Synth"), port(CowbellSymbol.BODY).enumLabels)
    }

    @Test
    fun `mode port shows Hold but keeps its value`() {
        val synth = CowbellMode.SYNTH
        assertEquals("Hold", port(CowbellSymbol.MODE).enumLabels?.get(synth.ordinal))
        assertEquals(2, synth.ordinal)
        plugin.setPortValue("mode", PortValue.IntValue(2))
        assertEquals(PortValue.IntValue(2), plugin.getPortValue("mode"))
    }

    @Test
    fun `strike and gate port writes read back empty`() {
        plugin.setPortValue("strike", PortValue.FloatValue(cowbellStrikeValue(1f)))
        plugin.setPortValue("gate", PortValue.BoolValue(true))
        assertEquals(0f, plugin.getPortValue("strike")?.asFloat())
        assertFalse(plugin.getPortValue("gate")?.asBoolean() ?: true)
    }

    @Test
    fun `body and tone keep their values within range`() {
        assertEquals(PortValue.IntValue(2), plugin.getPortValue("body"))
        assertEquals(PortValue.FloatValue(0f), plugin.getPortValue("tone"))
        plugin.setPortValue("body", PortValue.IntValue(0))
        plugin.setPortValue("tone", PortValue.FloatValue(0.8f))
        assertEquals(PortValue.IntValue(0), plugin.getPortValue("body"))
        assertEquals(PortValue.FloatValue(0.8f), plugin.getPortValue("tone"))
        plugin.setPortValue("body", PortValue.IntValue(9))
        plugin.setPortValue("tone", PortValue.FloatValue(4f))
        assertEquals(PortValue.IntValue(3), plugin.getPortValue("body"))
        assertEquals(PortValue.FloatValue(1f), plugin.getPortValue("tone"))
    }

    @Test
    fun `body and tone are saved in presets`() {
        assertFalse(port(CowbellSymbol.BODY).excludeFromPresets)
        assertFalse(port(CowbellSymbol.TONE).excludeFromPresets)
    }

    @Test
    fun `bend and the offsets keep their values`() {
        plugin.setPortValue("bend", PortValue.FloatValue(0.25f))
        plugin.setPortValue("structure_offset", PortValue.FloatValue(-0.2f))
        plugin.setPortValue("position_offset", PortValue.FloatValue(0.3f))
        assertEquals(0.25f, plugin.getPortValue("bend")?.asFloat())
        assertEquals(-0.2f, plugin.getPortValue("structure_offset")?.asFloat())
        assertEquals(0.3f, plugin.getPortValue("position_offset")?.asFloat())
    }
}
