package org.balch.orpheus.plugins.pulsar

import org.balch.orpheus.core.plugin.ControlPort
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `vibe_generation` is the release fence for the ENTIRE vibe, so it must be the last
 * declared control input — declaration order is what the replay path publishes in.
 *
 * `PulsarViewModel.applyVibe` writes every vibe port and bumps `vibe_generation` last, which
 * pairs with the acquire load in `unit_process_pulsar` (orpheus_unit_pulsar.cpp). But
 * `DspSynthEngine.syncNativeBridgeState` does not replay that write order — it walks
 * `plugin.ports` and pushes each in DECLARATION order. Declared early, the fence is published
 * to C++ before the hundreds of track/lick/arrangement ports it is supposed to fence, and an
 * audio block landing inside that window runs `load_vibe` against a torn snapshot: the new
 * generation with half the old vibe's data. `load_vibe` then stamps `current_vibe_generation`,
 * so the mismatch that would trigger a corrective reload is gone and the stale patterns play
 * for the rest of the session.
 *
 * This is the same data-before-fence contract the lick buffer documents in PulsarPlugin
 * ("data ports register BEFORE the length ports"), applied to the port that fences everything.
 */
class PulsarVibeGenerationFencePortTest {

    @Test
    fun `vibe_generation is declared last so the boot sync publishes it after the data it fences`() {
        val controlInputs = PulsarPlugin().ports
            .filterIsInstance<ControlPort>()
            .filter { it.isInput }

        val fenceIndex = controlInputs.indexOfFirst { it.symbol == PulsarSymbol.VIBE_GENERATION.symbol }
        assertNotNull(controlInputs.getOrNull(fenceIndex), "VIBE_GENERATION must be a declared control input")

        val after = controlInputs.drop(fenceIndex + 1).map { it.symbol }
        assertEquals(
            emptyList(), after,
            "vibe_generation must be the LAST declared control input — syncNativeBridgeState " +
                "replays ports in declaration order, so anything declared after it is published " +
                "to C++ AFTER the fence and can be read by load_vibe as a torn snapshot. " +
                "Declared at index $fenceIndex of ${controlInputs.size}.",
        )
    }
}
