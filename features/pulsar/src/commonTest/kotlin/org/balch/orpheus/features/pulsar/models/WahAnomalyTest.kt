package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WahAnomalyTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTripsThroughJson() {
        val w = WahAnomaly(
            probability = 0.2f, durationBarsMin = 1.5f, durationBarsMax = 6f,
            voice = WahParams(
                rateDivision = 4f, depth = 0.8f, resonanceQ = 5f,
                centerHz = 600f, sweepOctaves = 2f, wet = 0.7f,
            ),
        )
        val decoded = json.decodeFromString(
            WahAnomaly.serializer(),
            json.encodeToString(WahAnomaly.serializer(), w)
        )
        assertEquals(w, decoded)
    }

    @Test
    fun rejectsOutOfRangeProbability() {
        assertFailsWith<IllegalArgumentException> { WahAnomaly(probability = 1.5f) }
    }

    @Test
    fun rejectsInvertedDurationRange() {
        assertFailsWith<IllegalArgumentException> {
            WahAnomaly(durationBarsMin = 4f, durationBarsMax = 2f)
        }
    }

    @Test
    fun defaultsAreShipValues() {
        val w = WahAnomaly()
        assertEquals(0.03f, w.probability)
        assertEquals(2f, w.durationBarsMin)
        assertEquals(4f, w.durationBarsMax)
        assertEquals(WahParams(), w.voice)
    }

    @Test
    fun voiceDefaultsMirrorCppWahParams() {
        // These MUST match orpheus::WahParams (orpheus_wah_core.h) and the wah_data
        // marshal defaults in PulsarViewModel — the bank order is a hard contract.
        val v = WahParams()
        assertEquals(8f, v.rateDivision)
        assertEquals(1f, v.depth)
        assertEquals(3f, v.resonanceQ)
        assertEquals(800f, v.centerHz)
        assertEquals(1.3f, v.sweepOctaves)
        assertEquals(1f, v.wet)
    }
}
