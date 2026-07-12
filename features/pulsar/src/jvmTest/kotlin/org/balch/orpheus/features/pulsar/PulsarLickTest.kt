package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the Kotlin<->C++ marshalling cardinality for lick data: [PulsarSymbol] must expose
 * exactly `MAX_LICK_STEPS * LICK_FIELDS_PER_STEP` `LICK_DATA_*` symbols, and [Lick] must
 * reject more than `MAX_LICK_STEPS` steps. When a later task raises the cap, this test's
 * expectation drives the enum extension.
 */
class PulsarLickTest {

    @Test
    fun `lick symbol count matches MAX_LICK_STEPS x fields`() {
        val lickData = PulsarSymbol.entries.filter { it.name.startsWith("LICK_DATA_") }
        assertEquals(Lick.MAX_LICK_STEPS * Lick.LICK_FIELDS_PER_STEP, lickData.size)
        // LICK_DATA_COUNT is what PulsarPlugin sizes its port registration from —
        // pin it to the same contract so all three stay in lockstep.
        assertEquals(PulsarSymbol.LICK_DATA_COUNT, lickData.size)
    }

    @Test
    fun `Lick rejects more than MAX_LICK_STEPS`() {
        Lick(steps = List(Lick.MAX_LICK_STEPS) { LickStep(0, 0.25f, 0.8f) }) // ok
        assertFailsWith<IllegalArgumentException> {
            Lick(steps = List(Lick.MAX_LICK_STEPS + 1) { LickStep(0, 0.25f, 0.8f) })
        }
    }
}
