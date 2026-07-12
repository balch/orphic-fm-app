package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the plugin-side lick port registration to the [PulsarSymbol] marshalling
 * contract (LICK_DATA_0..255 = 64 steps x 4 fields).
 *
 * The native engine path writes by symbol regardless of registration, but preset
 * capture/restore (PortRegistry) and DspSynthEngine.syncNativeBridgeState() iterate
 * REGISTERED ports only — a partial registration silently truncates licks on those
 * paths (this happened: the plugin sat at 96 ports / 24 steps after the 64-step raise).
 */
class PulsarPluginLickPortsTest {

    private val lickDataSymbols =
        PulsarSymbol.entries.filter { it.name.startsWith("LICK_DATA_") }

    @Test
    fun `plugin registers every LICK_DATA symbol`() {
        val registered = PulsarPlugin().ports.map { it.symbol }.toSet()
        val missing = lickDataSymbols.filterNot { it.symbol in registered }
        assertTrue(
            missing.isEmpty(),
            "PulsarPlugin must register all ${lickDataSymbols.size} lick data ports, " +
                "missing ${missing.size} starting at ${missing.firstOrNull()?.symbol}"
        )
    }

    @Test
    fun `no port symbol is registered twice`() {
        // Guards every entries[ordinal + stride] loop in the plugin: a stale stride
        // walks past its enum block and re-registers neighboring symbols (stride 16
        // vs 14 in the macro-map loop once shadowed the genre + lick ports).
        val symbols = PulsarPlugin().ports.map { it.symbol }
        val dupes = symbols.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            dupes.isEmpty(),
            "duplicate port registrations (${symbols.size} total ports): " +
                dupes.entries.take(6).joinToString { "${it.key}×${it.value}" }
        )
    }

    @Test
    fun `plugin registers every track lick_degree_offset symbol`() {
        val family = PulsarSymbol.entries.filter {
            it.name.matches(Regex("TRACK_[0-7]_LICK_DEGREE_OFFSET"))
        }
        assertEquals(8, family.size, "expected 8 per-track lick_degree_offset symbols")
        val registered = PulsarPlugin().ports.map { it.symbol }.toSet()
        val missing = family.filterNot { it.symbol in registered }
        assertTrue(
            missing.isEmpty(),
            "missing ${missing.size} lick_degree_offset ports: ${missing.map { it.symbol }}"
        )
    }

    @Test
    fun `plugin registers every track macro-map symbol with the declared stride`() {
        val macroBlock = PulsarSymbol.entries.filter { it.name.matches(Regex("TRACK_[0-7]_MACRO_.*")) }
        assertEquals(
            8 * PulsarSymbol.TRACK_MACRO_STRIDE, macroBlock.size,
            "TRACK_MACRO_STRIDE no longer matches the enum's macro-map block"
        )
        val registered = PulsarPlugin().ports.map { it.symbol }.toSet()
        val missing = macroBlock.filterNot { it.symbol in registered }
        assertTrue(
            missing.isEmpty(),
            "missing ${missing.size} macro-map ports, first: ${missing.firstOrNull()?.symbol}"
        )
    }

    @Test
    fun `last lick data port round-trips a value`() {
        val plugin = PulsarPlugin()
        val last = lickDataSymbols.last()
        assertTrue(
            plugin.setPortValue(last.symbol, PortValue.FloatValue(0.42f)),
            "setPortValue(${last.symbol}) rejected — port not registered"
        )
        assertEquals(0.42f, plugin.getPortValue(last.symbol)?.asFloat())
    }

    @Test
    fun `lick data ports register before lick_length`() {
        // C++ stores lick_length with memory_order_release and reads it with acquire
        // (the "data first, length last as release fence" contract — see the lick push
        // in PulsarViewModel and orpheus_engine_routing.cpp). syncNativeBridgeState()
        // pushes ports in registration order, so data must precede LICK_LENGTH here.
        val symbols = PulsarPlugin().ports.map { it.symbol }
        val lengthIdx = symbols.indexOf(PulsarSymbol.LICK_LENGTH.symbol)
        val lastDataIdx = symbols.indexOf(lickDataSymbols.last().symbol)
        assertTrue(lengthIdx >= 0, "lick_length port not registered")
        assertTrue(lastDataIdx >= 0, "last lick_data port not registered")
        assertTrue(
            lastDataIdx < lengthIdx,
            "lick_data ports must register before lick_length so generic native sync " +
                "writes data before the length release-fence " +
                "(lick_length at $lengthIdx, ${lickDataSymbols.last().symbol} at $lastDataIdx)"
        )
    }
}
