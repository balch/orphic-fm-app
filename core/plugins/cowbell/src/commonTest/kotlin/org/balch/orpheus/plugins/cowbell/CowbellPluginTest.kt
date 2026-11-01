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
    fun `exactly eight ports are excluded from presets`() {
        val excluded = controls.values.filter { it.excludeFromPresets }.map { it.symbol }.toSet()
        assertEquals(
            setOf("strike", "gate", "bend", "structure_offset", "position_offset", "bend_scale", "live", "more"),
            excluded,
        )
    }

    @Test
    fun `defaults match the engine`() {
        assertEquals(0.7f, port(CowbellSymbol.LEVEL).default)
        assertEquals(0.84f, port(CowbellSymbol.METAL).default)
        assertEquals(0f, port(CowbellSymbol.RING).default)
        assertEquals(0.7f, port(CowbellSymbol.INTENSITY).default)
        assertEquals(-1f, port(CowbellSymbol.ENGINE).default)
        assertEquals(-1f, port(CowbellSymbol.ENGINE).min)
        assertEquals(24f, port(CowbellSymbol.ENGINE).max)
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
    fun `bendScalePortDefaultsOffAndIsExcludedFromPresets`() {
        val port = port(CowbellSymbol.BEND_SCALE)
        assertEquals("bend_scale", port.symbol)
        assertTrue(port.excludeFromPresets)
        assertEquals(PortValue.BoolValue(false), plugin.getPortValue("bend_scale"))
        plugin.setPortValue("bend_scale", PortValue.BoolValue(true))
        assertEquals(PortValue.BoolValue(true), plugin.getPortValue("bend_scale"))
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
    fun `engine default is the bare impulse`() {
        assertEquals(-1f, port(CowbellSymbol.ENGINE).default)
        assertEquals(PortValue.IntValue(-1), plugin.getPortValue("engine"))
    }

    @Test
    fun `enum sizes match the port ranges`() {
        assertEquals(port(CowbellSymbol.MODE).max.toInt() + 1, CowbellMode.entries.size)
        assertEquals(port(CowbellSymbol.FILL).max.toInt() + 1, CowbellFill.entries.size)
    }

    @Test
    fun `option labels follow the enums`() {
        assertEquals(listOf("Tap", "Shake", "Hold"), port(CowbellSymbol.MODE).enumLabels)
        assertEquals(listOf("Marble", "Beads", "Seeds", "Jingles"), port(CowbellSymbol.FILL).enumLabels)
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
    fun `bend and the offsets keep their values`() {
        plugin.setPortValue("bend", PortValue.FloatValue(0.25f))
        plugin.setPortValue("structure_offset", PortValue.FloatValue(-0.2f))
        plugin.setPortValue("position_offset", PortValue.FloatValue(0.3f))
        assertEquals(0.25f, plugin.getPortValue("bend")?.asFloat())
        assertEquals(-0.2f, plugin.getPortValue("structure_offset")?.asFloat())
        assertEquals(0.3f, plugin.getPortValue("position_offset")?.asFloat())
    }
}
