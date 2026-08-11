package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.audio.UNAUTHORED_TENSION_BOUND
import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tension-evolution bound defaults to unauthored on all four mirrors: this plugin's
 * port default and backing field, `EvolutionTension` in features/pulsar, and the C++
 * `pulsar_tension_evo_*` atomics.
 *
 * The plugin is the copy that drifts, because nothing reads it until a preset or reset
 * pushes the getter — TIMBRE_LOW/HIGH sat at a hardcoded 0.25/0.55 here while the other
 * three mirrors moved to the sentinel.
 */
class PulsarTensionBoundDefaultsTest {

    private val boundSymbols = PulsarSymbol.entries.filter {
        it.name.startsWith("TENSION_EVO_") && (it.name.endsWith("_LOW") || it.name.endsWith("_HIGH"))
    }

    private fun boundPorts(): List<ControlPort> {
        val wanted = boundSymbols.map { it.symbol }.toSet()
        return PulsarPlugin().ports.filterIsInstance<ControlPort>().filter { it.symbol in wanted }
    }

    @Test
    fun `every tension evo bound port defaults to unauthored`() {
        // timbre, morph, harmonics x low/high. A rename that empties this list would let
        // the guard pass while checking nothing.
        assertEquals(6, boundSymbols.size, "expected 6 tension evo bound symbols, found $boundSymbols")
        assertEquals(6, boundPorts().size, "not every tension evo bound symbol is registered as a port")

        val drifted = boundPorts().filter { it.default != UNAUTHORED_TENSION_BOUND }
        assertTrue(
            drifted.isEmpty(),
            "these ports default to a real value instead of UNAUTHORED_TENSION_BOUND, so a " +
                "preset or reset re-authors a window the vibe never chose: " +
                drifted.joinToString { "${it.symbol}=${it.default}" },
        )
    }

    @Test
    fun `bound ports admit the sentinel`() {
        val tooHigh = boundPorts().filter { it.min > UNAUTHORED_TENSION_BOUND }
        assertTrue(
            tooHigh.isEmpty(),
            "these ports clamp above the sentinel, so the unauthored default is unreachable " +
                "through the port: ${tooHigh.joinToString { "${it.symbol} min=${it.min}" }}",
        )
    }
}
