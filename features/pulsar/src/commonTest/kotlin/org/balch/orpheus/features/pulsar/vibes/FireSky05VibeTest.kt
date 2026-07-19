package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FireSky05VibeTest {
    private val vibe = FireSky05Vibe().vibe

    @Test
    fun name_matches_catalog_key_exactly() {
        assertEquals("Fire Sky .5f", FireSky05Vibe().name)
        assertTrue("Fire Sky .5f" in VibeCatalog.entries.keys, "catalog must list 'Fire Sky .5f'")
    }

    @Test
    fun catalogued_as_live() {
        // Promoted 2026-07-18: .5f ships (60bpm flat + the rare founding-hook anomaly).
        assertEquals(VibeStatus.LIVE, VibeCatalog.entries["Fire Sky .5f"]?.status)
    }

    @Test
    fun flat_60_bpm_no_half_time() {
        assertEquals(60f, vibe.bpm)
    }

    @Test
    fun intro_build_run_at_base_60_bpm_flat() {
        // intro/build sections run at 1.0f multiplier (no half-time) = full 60 BPM
        val sections = vibe.arrangement?.sections.orEmpty()
        val flatSections = sections.filter { it.name == "intro" || it.name == "build" }
        assertTrue(flatSections.isNotEmpty(), "expected intro/build sections")
        flatSections.forEach {
            assertEquals(1.0f, it.bpmMultiplier,
                "${it.name}: multiplier must be 1.0f (flat 60 BPM)")
            assertEquals(60f, vibe.bpm * it.bpmMultiplier,
                "${it.name}: ${vibe.bpm * it.bpmMultiplier} must equal 60 BPM (flat)")
        }
    }

    @Test
    fun rotates_two_licks() {
        val r = vibe.lickRotation
        assertNotNull(r)
        assertEquals(2, r.pool.size)
    }

    @Test
    fun wires_og_lick_anomaly() {
        // The founding hook rides as a LickAnomaly in vibe.anomalies, not inside the rotation.
        val anomaly = vibe.anomalies.filterIsInstance<LickAnomaly>().singleOrNull()
        assertNotNull(anomaly)                   // the founding hook is the anomaly lick
        assertEquals(0.02f, anomaly.chance)      // rare ship rate
        // It is NOT a rotation member — the pool stays the two rotating licks:
        val r = vibe.lickRotation
        assertNotNull(r)
        assertEquals(2, r.pool.size)
    }
}
