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
    fun `plugin registers every track OSC FM symbol on both engine slots`() {
        // The FM families shipped as routing rows only. An engine recreation
        // (DesktopEngine::open, an Android headphone route change) zeroes the C++
        // atomics and syncNativeBridgeState replays declared ports alone, so an
        // OSC track lost its FM mid-session while harmonics/timbre/morph came back.
        val plugin = PulsarPlugin()
        val registered = plugin.ports.map { it.symbol }.toSet()
        for (family in listOf("FM_RATIO", "FM_SHAPE", "FM_FREE_HZ")) {
            for (suffix in listOf("", "_SPACE")) {
                val symbols = PulsarSymbol.entries.filter {
                    it.name.matches(Regex("TRACK_[0-7]_$family$suffix"))
                }
                assertEquals(8, symbols.size, "expected 8 TRACK_n_$family$suffix symbols")
                val missing = symbols.filterNot { it.symbol in registered }
                assertTrue(
                    missing.isEmpty(),
                    "missing ${missing.size} $family$suffix ports: ${missing.map { it.symbol }}"
                )
            }
        }
    }

    @Test
    fun `every OSC FM port stores a written value for the boot sync to replay`() {
        // Registration alone is not the contract: syncNativeBridgeState re-pushes
        // getPortValue(), so a port that accepts a write without storing it is
        // just as lost. Distinct values also catch a stride that shadows a neighbour.
        val plugin = PulsarPlugin()
        val written = PulsarSymbol.entries
            .filter { it.name.matches(Regex("TRACK_[0-7]_FM_(RATIO|SHAPE|FREE_HZ)(_SPACE)?")) }
            .mapIndexed { i, sym -> sym to (i + 1) * 0.01f }
        assertEquals(48, written.size, "expected 6 families x 8 tracks")
        written.forEach { (sym, v) ->
            assertTrue(plugin.setPortValue(sym.symbol, PortValue.FloatValue(v)),
                "setPortValue(${sym.symbol}) rejected — port not registered")
        }
        written.forEach { (sym, v) ->
            assertEquals(v, plugin.getPortValue(sym.symbol)?.asFloat(),
                "${sym.symbol} did not hold its value — a later port shadows it")
        }
    }

    @Test
    fun `plugin registers every track vibrato port once and each holds its value`() {
        val plugin = PulsarPlugin()
        val registered = plugin.ports.map { it.symbol }
        var n = 0
        for (family in listOf("VIBRATO_DEPTH", "VIBRATO_RATE")) {
            for (suffix in listOf("", "_SPACE")) {
                val symbols = PulsarSymbol.entries.filter {
                    it.name.matches(Regex("TRACK_[0-7]_$family$suffix"))
                }
                assertEquals(8, symbols.size, "expected 8 TRACK_n_$family$suffix symbols")
                symbols.forEachIndexed { t, sym ->
                    assertEquals("track_${t}_${family.lowercase()}${suffix.lowercase()}", sym.symbol)
                    assertEquals(1, registered.count { it == sym.symbol }, "${sym.symbol} registered once")
                    // Distinct values, so a stride that shadows a neighbour shows up.
                    n++
                    assertTrue(plugin.setPortValue(sym.symbol, PortValue.FloatValue(n * 0.5f)),
                        "setPortValue(${sym.symbol}) rejected")
                }
            }
        }
        n = 0
        for (family in listOf("VIBRATO_DEPTH", "VIBRATO_RATE")) {
            for (suffix in listOf("", "_SPACE")) {
                PulsarSymbol.entries.filter { it.name.matches(Regex("TRACK_[0-7]_$family$suffix")) }
                    .forEach { sym ->
                        n++
                        assertEquals(n * 0.5f, plugin.getPortValue(sym.symbol)?.asFloat(), sym.symbol)
                    }
            }
        }
    }

    @Test
    fun `plugin registers every track pitch rise port once and each holds its value`() {
        val plugin = PulsarPlugin()
        val registered = plugin.ports.map { it.symbol }
        var n = 0
        for (family in listOf("PITCH_RISE", "PITCH_RISE_TIME")) {
            for (suffix in listOf("", "_SPACE")) {
                val symbols = PulsarSymbol.entries.filter {
                    it.name.matches(Regex("TRACK_[0-7]_$family$suffix"))
                }
                assertEquals(8, symbols.size, "expected 8 TRACK_n_$family$suffix symbols")
                symbols.forEachIndexed { t, sym ->
                    assertEquals("track_${t}_${family.lowercase()}${suffix.lowercase()}", sym.symbol)
                    assertEquals(1, registered.count { it == sym.symbol }, "${sym.symbol} registered once")
                    // Distinct values, so a stride that shadows a neighbour shows up.
                    n++
                    assertTrue(plugin.setPortValue(sym.symbol, PortValue.FloatValue(n * 0.5f)),
                        "setPortValue(${sym.symbol}) rejected")
                }
            }
        }
        n = 0
        for (family in listOf("PITCH_RISE", "PITCH_RISE_TIME")) {
            for (suffix in listOf("", "_SPACE")) {
                PulsarSymbol.entries.filter { it.name.matches(Regex("TRACK_[0-7]_$family$suffix")) }
                    .forEach { sym ->
                        n++
                        assertEquals(n * 0.5f, plugin.getPortValue(sym.symbol)?.asFloat(), sym.symbol)
                    }
            }
        }
    }

    @Test
    fun `plugin registers every track harmonics rise port once and each holds its value`() {
        val plugin = PulsarPlugin()
        val registered = plugin.ports.map { it.symbol }
        var n = 0
        for (family in listOf("HARMONICS_RISE", "HARMONICS_RISE_TIME")) {
            for (suffix in listOf("", "_SPACE")) {
                val symbols = PulsarSymbol.entries.filter {
                    it.name.matches(Regex("TRACK_[0-7]_$family$suffix"))
                }
                assertEquals(8, symbols.size, "expected 8 TRACK_n_$family$suffix symbols")
                symbols.forEachIndexed { t, sym ->
                    assertEquals("track_${t}_${family.lowercase()}${suffix.lowercase()}", sym.symbol)
                    assertEquals(1, registered.count { it == sym.symbol }, "${sym.symbol} registered once")
                    // Distinct values, so a stride that shadows a neighbour shows up.
                    n++
                    assertTrue(plugin.setPortValue(sym.symbol, PortValue.FloatValue(n * 0.5f)),
                        "setPortValue(${sym.symbol}) rejected")
                }
            }
        }
        n = 0
        for (family in listOf("HARMONICS_RISE", "HARMONICS_RISE_TIME")) {
            for (suffix in listOf("", "_SPACE")) {
                PulsarSymbol.entries.filter { it.name.matches(Regex("TRACK_[0-7]_$family$suffix")) }
                    .forEach { sym ->
                        n++
                        assertEquals(n * 0.5f, plugin.getPortValue(sym.symbol)?.asFloat(), sym.symbol)
                    }
            }
        }
    }

    private val grainFamilies = listOf(
        "GRAIN_MODE", "GRAIN_SCATTER_MODE", "GRAIN_SCATTER", "GRAIN_RATE",
        "GRAIN_RANDOM_TIMING", "GRAIN_SHAPE", "GRAIN_BLEND", "GRAIN_VOICING",
        "GRAIN_FX", "GRAIN_SIZE",
    )

    @Test
    fun `plugin registers every track Mist grain symbol on both engine slots`() {
        // An engine recreation zeroes the C++ atomics and syncNativeBridgeState replays
        // declared ports alone, so an undeclared grain port would reset mid-session.
        val plugin = PulsarPlugin()
        val registered = plugin.ports.map { it.symbol }
        for (family in grainFamilies) {
            for (suffix in listOf("", "_SPACE")) {
                val symbols = PulsarSymbol.entries.filter {
                    it.name.matches(Regex("TRACK_[0-7]_$family$suffix"))
                }
                assertEquals(8, symbols.size, "expected 8 TRACK_n_$family$suffix symbols")
                symbols.forEachIndexed { t, sym ->
                    assertEquals("track_${t}_${family.lowercase()}${suffix.lowercase()}", sym.symbol)
                }
                val missing = symbols.filterNot { it.symbol in registered }
                assertTrue(
                    missing.isEmpty(),
                    "missing ${missing.size} $family$suffix ports: ${missing.map { it.symbol }}"
                )
                // A second registration shadows the first, and find-first then loses writes.
                symbols.forEach { sym ->
                    assertEquals(1, registered.count { it == sym.symbol }, "${sym.symbol} registered twice")
                }
            }
        }
    }

    @Test
    fun `every Mist grain port stores a written value for the boot sync to replay`() {
        val plugin = PulsarPlugin()
        val floats = PulsarSymbol.entries.filter {
            it.name.matches(Regex("TRACK_[0-7]_GRAIN_(SCATTER|RATE|SHAPE|BLEND|SIZE)(_SPACE)?"))
        }
        val ints = PulsarSymbol.entries.filter {
            it.name.matches(Regex("TRACK_[0-7]_GRAIN_(MODE|SCATTER_MODE|RANDOM_TIMING|VOICING|FX)(_SPACE)?"))
        }
        assertEquals(80, floats.size, "expected 5 float families x 2 slots x 8 tracks")
        assertEquals(80, ints.size, "expected 5 int families x 2 slots x 8 tracks")
        // Distinct values, so a stride that shadows a neighbour shows up.
        floats.forEachIndexed { i, sym ->
            assertTrue(plugin.setPortValue(sym.symbol, PortValue.FloatValue((i + 1) * 0.01f)),
                "setPortValue(${sym.symbol}) rejected")
        }
        ints.forEachIndexed { i, sym ->
            assertTrue(plugin.setPortValue(sym.symbol, PortValue.IntValue(i % 3)),
                "setPortValue(${sym.symbol}) rejected")
        }
        floats.forEachIndexed { i, sym ->
            assertEquals((i + 1) * 0.01f, plugin.getPortValue(sym.symbol)?.asFloat(), sym.symbol)
        }
        ints.forEachIndexed { i, sym ->
            assertEquals(i % 3, plugin.getPortValue(sym.symbol)?.asInt(), sym.symbol)
        }
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
