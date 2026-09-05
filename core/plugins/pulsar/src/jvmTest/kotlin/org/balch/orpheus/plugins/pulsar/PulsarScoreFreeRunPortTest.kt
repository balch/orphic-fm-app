package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SCORE_FREE_RUN must be a declared, stored plugin port — not just a routing row.
 *
 * The desktop boot sequence recreates the C++ engine (DesktopEngine::open calls
 * orpheus_engine_create), so any port written before audio starts is wiped. The only
 * state that survives is what syncNativeBridgeState's generic loop re-pushes: declared
 * ControlPort inputs whose value the Kotlin plugin stored. A score host writes this flag at
 * composition time, before boot — without a declared port it silently never arrives.
 */
class PulsarScoreFreeRunPortTest {

    @Test
    fun `score_free_run is a declared control input so the boot sync replays it`() {
        val plugin = PulsarPlugin()
        val port = plugin.ports.firstOrNull { it.symbol == PulsarSymbol.SCORE_FREE_RUN.symbol }
        assertNotNull(port, "SCORE_FREE_RUN must be declared on the plugin, not just in routing")
        assertTrue(port is ControlPort && port.isInput, "The generic sync loop only walks ControlPort inputs")
    }

    @Test
    fun `a stored write survives to be re-pushed after engine recreation`() {
        val plugin = PulsarPlugin()
        assertTrue(
            plugin.setPortValue(PulsarSymbol.SCORE_FREE_RUN.symbol, IntValue(1)),
            "setPluginPort offers every write to the plugin; it must accept this one",
        )
        assertEquals(1, plugin.getPortValue(PulsarSymbol.SCORE_FREE_RUN.symbol)?.asInt())
    }

    @Test
    fun `defaults to zero so conducting still parks on holds out of the box`() {
        val plugin = PulsarPlugin()
        assertEquals(0, plugin.getPortValue(PulsarSymbol.SCORE_FREE_RUN.symbol)?.asInt())
    }
}
